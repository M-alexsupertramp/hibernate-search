/*
 * Hibernate Search, full-text search for your domain model
 *
 * License: GNU Lesser General Public License (LGPL), version 2.1 or later
 * See the lgpl.txt file in the root directory or <http://www.gnu.org/licenses/lgpl-2.1.html>.
 */
package org.hibernate.search.elasticsearch.util.impl;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.io.Writer;
import java.nio.ByteBuffer;
import java.nio.CharBuffer;
import java.nio.charset.Charset;
import java.nio.charset.CharsetEncoder;
import java.nio.charset.CoderResult;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.ArrayDeque;
import java.util.Deque;
import java.util.Iterator;
import java.util.List;
import java.util.Objects;

import org.hibernate.search.elasticsearch.spi.DigestSelfSigningCapable;
import org.hibernate.search.exception.SearchException;

import com.google.gson.Gson;
import com.google.gson.JsonObject;
import org.apache.http.Header;
import org.apache.http.HttpEntity;
import org.apache.http.entity.ContentType;
import org.apache.http.message.BasicHeader;
import org.apache.http.nio.ContentEncoder;
import org.apache.http.nio.IOControl;
import org.apache.http.nio.entity.HttpAsyncContentProducer;
import org.apache.http.protocol.HTTP;

/**
 * Optimised adapter to encode GSON objects into HttpEntity instances.
 * The naive approach was using various StringBuilders; the objects we
 * need to serialise into JSON might get large and this was causing the
 * internal StringBuilder buffers to need frequent resizing and cause
 * problems with excessive allocations.
 *
 * Rather than trying to guess reasonable default sizes for these buffers,
 * we can defer the serialisation to write directly into the ByteBuffer
 * of the HTTP client, this has the additional benefit of making the
 * intermediary buffers short lived.
 *
 * The one complexity to watch for is flow control: when writing into
 * the output buffer chances are that not all bytes are accepted; in
 * this case we have to hold on the remaining portion of data to
 * be written when the flow control is re-enabled.
 *
 * A side effect of this strategy is that the total content length which
 * is being produced is not known in advance. Not reporting the length
 * in advance to the Apache Http client causes it to use chunked-encoding,
 * which is great for large blocks but not optimal for small messages.
 * For this reason we attempt to start encoding into a small buffer
 * upfront: if all data we need to produce fits into that then we can
 * report the content length; if not the encoding completion will be deferred
 * but not resetting so to avoid repeating encoding work.
 *
 * @author Sanne Grinovero (C) 2017 Red Hat Inc.
 */
public final class GsonHttpEntity implements HttpEntity, HttpAsyncContentProducer, DigestSelfSigningCapable {

	private static final Charset CHARSET = StandardCharsets.UTF_8;

	private static final byte[] NEWLINE = "\n".getBytes( CHARSET );

	private static final BasicHeader CONTENT_TYPE = new BasicHeader( HTTP.CONTENT_TYPE, ContentType.APPLICATION_JSON.toString() );

	/**
	 * The size of byte buffer pages in {@link ProgressiveCharBufferWriter}
	 * It's a rather large size: a tradeoff for very large JSON
	 * documents as we do heavy bulking, and not too large to
	 * be a penalty for small requests.
	 * 1024 has been shown to produce reasonable, TLAB only garbage.
	 */
	private static final int BUFFER_PAGE_SIZE = 1024;

	private final Gson gson;
	private final List<JsonObject> bodyParts;

	/**
	 * We don't want to compute the length in advance as it would defeat the optimisations
	 * for large bulks.
	 * Still it's possible that we happen to find out, for example if a Digest from all
	 * content needs to be computed, or if the content is small enough as we attempt
	 * to serialise at least one page.
	 */
	private long contentLength;

	/**
	 * We can lazily compute the contentLenght, but we need to avoid changing the value
	 * we report over time as this confuses the Apache HTTP client as it initially defines
	 * the encoding strategy based on this, then assumes it can rely on this being
	 * a constant.
	 * After the {@link #getContentLength()} was invoked at least once, freeze
	 * the value.
	 */
	private boolean contentlengthWasProvided = false;

	/**
	 * Since flow control might hint to stop producing data,
	 * while we can't interrupt the rendering of a single JSON body
	 * we can avoid starting the rendering of any subsequent JSON body.
	 * So keep track of the next body which still needs to be rendered;
	 * to allow the output to be "repeatable" we also need to reset this
	 * at the end.
	 */
	private int nextBodyToEncodeIndex = 0;

	/**
	 * Adaptor from string output rendered into the actual output sink.
	 * We keep this as a field level attribute as we might have
	 * partially rendered JSON stored in its buffers while flow control
	 * refuses to accept more bytes.
	 */
	private ProgressiveCharBufferWriter writer = new ProgressiveCharBufferWriter( CHARSET, BUFFER_PAGE_SIZE );

	public GsonHttpEntity(Gson gson, List<JsonObject> bodyParts) {
		Objects.requireNonNull( gson );
		Objects.requireNonNull( bodyParts );
		this.gson = gson;
		this.bodyParts = bodyParts;
		this.contentLength = -1;
		attemptOnePassEncoding();
	}

	@Override
	public boolean isRepeatable() {
		return true;
	}

	@Override
	public boolean isChunked() {
		return false;
	}

	@Override
	public long getContentLength() {
		this.contentlengthWasProvided = true;
		return this.contentLength;
	}

	@Override
	public Header getContentType() {
		return CONTENT_TYPE;
	}

	@Override
	public Header getContentEncoding() {
		//Apparently this is the correct value:
		return null;
	}

	@Override
	public InputStream getContent() throws IOException {
		//This could be implemented but would be sub-optimal compared to using produceContent().
		//We therefore prefer throwing the exception so that we can easily spot unintended usage via tests.
		throw new UnsupportedOperationException( "Not implemented! Expected to produce content only over produceContent()" );
	}

	@Override
	public void writeTo(OutputStream outstream) throws IOException {
		//This could be implemented but would be sub-optimal compared to using produceContent().
		//We therefore prefer throwing the exception so that we can easily spot unintended usage via tests.
		throw new UnsupportedOperationException( "Not implemented! Expected to produce content only over produceContent()" );
	}

	@Override
	public boolean isStreaming() {
		return false;
	}

	@Override
	public void consumeContent() throws IOException {
		//not used (and deprecated)
	}

	@Override
	public void close() throws IOException {
		//Nothing to close but let's make sure we re-wind the stream
		//so that we can start from the beginning if needed
		this.nextBodyToEncodeIndex = 0;
		//Discard previous buffers as they might contain in-process content:
		this.writer = new ProgressiveCharBufferWriter( CHARSET, BUFFER_PAGE_SIZE );
	}

	/**
	 * Let's see if we can fully encode the content with a minimal write,
	 * i.e. only one body part.
	 * This will allow us to keep the memory consumption reasonable
	 * while also being able to hint the client about the {@link #getContentLength()}.
	 * Incidentally, having this information would avoid chunked output encoding
	 * which is ideal precisely for small messages which can fit into a single buffer.
	 */
	private void attemptOnePassEncoding() {
		// Essentially attempt to use the writer without going NPE on the output sink
		// as it's not set yet.
		try {
			triggerFullWrite();
		}
		catch (IOException e) {
			// Unlikely: there's no output buffer yet!
			throw new SearchException( e );
		}
		if ( writer.isFlowControlPushingBack() == false ) {
			// We may not have written everything yet, but the content-size is final,
			// as we know the entire content has been rendered already.
			hintContentLength( writer.bufferedContentSize() );
		}
	}

	/**
	 * Higher level write loop. It will start writing the JSON objects
	 * from either the  beginning or the next object which wasn't written yet
	 * but simply stop and return as soon as the sink can't accept more data.
	 * Checking state of writer.flowControlPushingBack will reveal if everything
	 * was written.
	 * @throws IOException
	 */
	private void triggerFullWrite() throws IOException {
		while ( nextBodyToEncodeIndex < bodyParts.size() ) {
			JsonObject bodyPart = bodyParts.get( nextBodyToEncodeIndex++ );
			gson.toJson( bodyPart, writer );
			writer.append( '\n' );
			writer.flush();
			if ( writer.isFlowControlPushingBack() ) {
				//Just quit: return control to the caller and trust we'll be called again.
				return;
			}
		}
	}

	@Override
	public void produceContent(ContentEncoder encoder, IOControl ioctrl) throws IOException {
		Objects.requireNonNull( encoder );
		// Warning: this method is possibly invoked multiple times, depending on the output buffers
		// to have available space !
		// Production of data is expected to complete only after we invoke ContentEncoder#complete.

		//Re-set the encoder as it might be a different one than a previously used instance:
		writer.setOutput( encoder );

		//First write unfinished business from previous attempts
		writer.resumePendingWrites();
		if ( writer.isFlowControlPushingBack() ) {
			//Just quit: return control to the caller and trust we'll be called again.
			return;
		}

		triggerFullWrite();

		if ( writer.isFlowControlPushingBack() ) {
			//Just quit: return control to the caller and trust we'll be called again.
			return;
		}
		writer.flushToOutput();
		if ( writer.isFlowControlPushingBack() ) {
			//Just quit: return control to the caller and trust we'll be called again.
			return;
		}
		// If we haven't aborted yet, we finished!
		encoder.complete();

		// Design note: we could finally know the content length in bytes at this point
		// (we had an accumulator in previous versions) but that's always pointless
		// as the HTTP CLient will request the size before starting produce content.

		//Allow to repeat the content rendering from the beginning:
		this.nextBodyToEncodeIndex = 0;
	}

	@Override
	public void fillDigest(MessageDigest digest) throws IOException {
		//For digest computation we use no pagination, so ignore the mutable fields.
		final DigestWriter digestWriter = new DigestWriter( digest );
		for ( JsonObject bodyPart : bodyParts ) {
			gson.toJson( bodyPart, digestWriter );
			digestWriter.insertNewline();
		}
		//Now we finally know the content size in bytes:
		hintContentLength( digestWriter.getContentLength() );
	}

	private void hintContentLength(long contentLength) {
		if ( contentlengthWasProvided == false ) {
			this.contentLength = contentLength;
		}
	}

	/**
	 * A writer to a ContentEncoder, using an automatically growing, paged buffer
	 * to store input when flow control pushes back.
	 * <p>
	 * To be used when your input source is not reactive (uses {@link Writer}),
	 * but you have multiple elements to write and thus could take advantage of
	 * reactive output to some extent.
	 *
	 * @author Sanne Grinovero
	 * @author Yoann Rodiere
	 */
	static class ProgressiveCharBufferWriter extends Writer {

		private final CharsetEncoder charsetEncoder;

		/**
		 * Size of buffer pages.
		 */
		private final int pageSize;

		/**
		 * Filled buffer pages to be written, in write order.
		 */
		private final Deque<ByteBuffer> needWritingPages = new ArrayDeque<>( 5 );

		/**
		 * Current buffer page, potentially null,
		 * which may have some content but isn't full yet.
		 */
		private ByteBuffer currentPage;

		/**
		 * Initially null: must be set before writing is started and each
		 * time it's resumed as it might change between writes during
		 * chunked encoding.
		 */
		private ContentEncoder output;

		/**
		 * Set this to true when we detect clogging, so we can stop trying.
		 * Make sure to reset this when the HTTP Client hints so.
		 * It's never dangerous to re-enable, just not efficient to try writing
		 * unnecessarily.
		 */
		private boolean flowControlPushingBack = false;

		public ProgressiveCharBufferWriter(Charset charset, int pageSize) {
			this.charsetEncoder = charset.newEncoder();
			this.pageSize = pageSize;
		}

		/**
		 * Set the encoder to write to when buffers are full.
		 */
		public void setOutput(ContentEncoder output) {
			this.output = output;
		}

		@Override
		public void write(char[] cbuf, int off, int len) throws IOException {
			CharBuffer input = CharBuffer.wrap( cbuf, off, len );
			while ( true ) {
				if ( currentPage == null ) {
					currentPage = ByteBuffer.allocate( pageSize );
				}
				CoderResult coderResult = charsetEncoder.encode( input, currentPage, false );
				if ( coderResult.equals( CoderResult.UNDERFLOW ) ) {
					return;
				}
				else if ( coderResult.equals( CoderResult.OVERFLOW ) ) {
					// Avoid storing buffers if we can simply flush them
					attemptFlushPendingBuffers( true );
					if ( currentPage != null ) {
					/*
					 * We couldn't flush the current page, but it's full,
					 * so let's move it out of the way.
					 */
						currentPage.flip();
						needWritingPages.add( currentPage );
						currentPage = null;
					}
				}
				else {
					//Encoding exception
					coderResult.throwException();
					return; //Unreachable
				}
			}
		}

		@Override
		public void flush() throws IOException {
			// don't flush for real as we want to control actual flushing independently.
		}

		@Override
		public void close() throws IOException {
			// Nothing to do
		}

		/**
		 * Send all full buffer pages to the {@link #setOutput(ContentEncoder) output}.
		 * <p>
		 * Flow control may push back, in which case this method or {@link #flushToOutput()}
		 * should be called again later.
		 *
		 * @throws IOException when {@link ContentEncoder#write(ByteBuffer)} fails.
		 */
		public void resumePendingWrites() throws IOException {
			flowControlPushingBack = false;
			attemptFlushPendingBuffers( false );
		}

		/**
		 * @return {@code true} if the {@link #setOutput(ContentEncoder) output} pushed
		 * back the last time a write was attempted, {@code false} otherwise.
		 */
		public boolean isFlowControlPushingBack() {
			return flowControlPushingBack;
		}

		/**
		 * Send all buffer pages to the {@link #setOutput(ContentEncoder) output},
		 * Even those that are not full yet
		 * <p>
		 * Flow control may push back, in which case this method should be called again later.
		 *
		 * @throws IOException when {@link ContentEncoder#write(ByteBuffer)} fails.
		 */
		public void flushToOutput() throws IOException {
			flowControlPushingBack = false;
			attemptFlushPendingBuffers( true );
		}

		/**
		 * @return The current size of content stored in the buffer, in bytes.
		 * This does not include the content that has already been written to the {@link #setOutput(ContentEncoder) output}.
		 */
		public int bufferedContentSize() {
			int contentSize = 0;
		/*
		 * We cannot just multiply the number of pages by the page size,
		 * because the encoder may overflow without filling a page in some
		 * cases (for instance when there's only 1 byte of space available in
		 * the buffer, and the encoder needs to write two bytes for a single char).
		 */
			for ( ByteBuffer page : needWritingPages ) {
				contentSize += page.remaining();
			}
			if ( currentPage != null ) {
			/*
			 * Add the size of the current page using position(),
			 * since it hasn't been flipped yet.
			 */
				contentSize += currentPage.position();
			}
			return contentSize;
		}

		/**
		 * @return {@code true} if this buffer contains content to be written, {@code false} otherwise.
		 */
		private boolean hasRemaining() {
			return !needWritingPages.isEmpty() || currentPage != null && currentPage.position() > 0;
		}

		private void attemptFlushPendingBuffers(boolean flushCurrentPage) throws IOException {
			if ( output == null ) {
				flowControlPushingBack = true;
			}
			if ( flowControlPushingBack || !hasRemaining() ) {
				// Nothing to do
				return;
			}
			Iterator<ByteBuffer> iterator = needWritingPages.iterator();
			while ( iterator.hasNext() && !flowControlPushingBack ) {
				ByteBuffer buffer = iterator.next();
				boolean written = write( buffer );
				if ( written ) {
					iterator.remove();
				}
				else {
					flowControlPushingBack = true;
				}
			}
			if ( flushCurrentPage && !flowControlPushingBack && currentPage != null && currentPage.position() > 0 ) {
				// The encoder still accepts some input, and we are allowed to flush the current page. Let's do.
				currentPage.flip();
				boolean written = write( currentPage );
				if ( !written ) {
					flowControlPushingBack = true;
					needWritingPages.add( currentPage );
				}
				currentPage = null;
			}
		}

		private boolean write(ByteBuffer buffer) throws IOException {
			final int toWrite = buffer.remaining();
			// We should never do 0-length writes, see HSEARCH-2854
			if ( toWrite == 0 ) {
				return true;
			}
			final int actuallyWritten = output.write( buffer );
			return toWrite == actuallyWritten;
		}

	}

	private static final class DigestWriter extends Writer {

		private final MessageDigest digest;
		private long totalWrittenBytes = 0;

		public DigestWriter(MessageDigest digest) {
			this.digest = digest;
		}

		@Override
		public void write(char[] input, int offset, int len) throws IOException {
			CharBuffer charBuffer = CharBuffer.wrap( input, offset, len );
			ByteBuffer byteBuffer = StandardCharsets.UTF_8.encode( charBuffer );
			this.totalWrittenBytes += byteBuffer.remaining();
			this.digest.update( byteBuffer );
		}

		@Override
		public void flush() throws IOException {
			// Nothing to do
		}

		@Override
		public void close() throws IOException {
			// Nothing to do
		}

		public void insertNewline() {
			this.totalWrittenBytes += NEWLINE.length;
			this.digest.update( NEWLINE );
		}

		public long getContentLength() {
			return this.totalWrittenBytes;
		}

	}

}