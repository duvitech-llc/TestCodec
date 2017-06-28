package com.duvitech.testcodec;

import android.content.res.AssetFileDescriptor;
import android.content.res.Resources;
import android.graphics.ImageFormat;
import android.graphics.Rect;
import android.media.Image;
import android.media.ImageReader;
import android.media.MediaCodec;
import android.media.MediaCodecInfo;
import android.media.MediaCodecList;
import android.media.MediaExtractor;
import android.media.MediaFormat;
import android.os.Handler;
import android.os.HandlerThread;
import android.support.v7.app.AppCompatActivity;
import android.os.Bundle;
import android.util.Log;
import android.view.Surface;

import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.ByteBuffer;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.TimeUnit;

import static android.media.MediaCodecInfo.CodecCapabilities.COLOR_FormatYUV420Flexible;
import static android.util.Log.VERBOSE;
import static junit.framework.Assert.assertEquals;
import static junit.framework.Assert.assertNotNull;
import static junit.framework.Assert.assertTrue;
import static junit.framework.Assert.fail;

public class MainActivity extends AppCompatActivity {

    static final float SWIRL_FPS = 12.f;
    static String TAG = "Decoder";

    private static final long DEFAULT_TIMEOUT_US = 10000;
    private static final long WAIT_FOR_IMAGE_TIMEOUT_MS = 1000;
    private static final String DEBUG_FILE_NAME_BASE = "/sdcard/";
    private static final int NUM_FRAME_DECODED = 100;
    // video decoders only support a single outstanding image with the consumer
    private static final int MAX_NUM_IMAGES = 1;
    private static final float COLOR_STDEV_ALLOWANCE = 5f;
    private static final float COLOR_DELTA_ALLOWANCE = 5f;
    private final static int MODE_IMAGEREADER = 0;
    private final static int MODE_IMAGE       = 1;

    private Resources mResources;
    private MediaCodec.BufferInfo mBufferInfo = new MediaCodec.BufferInfo();
    private ImageReader mReader;
    private Surface mReaderSurface;
    private HandlerThread mHandlerThread;
    private Handler mHandler;
    private ImageListener mImageListener;


    static class MediaAsset {
        public MediaAsset(int resource, int width, int height) {
            mResource = resource;
            mWidth = width;
            mHeight = height;
        }
        public int getWidth() {
            return mWidth;
        }
        public int getHeight() {
            return mHeight;
        }
        public int getResource() {
            return mResource;
        }
        private final int mResource;
        private final int mWidth;
        private final int mHeight;
    }

    static class MediaAssets {
        public MediaAssets(String mime, MediaAsset... assets) {
            mMime = mime;
            mAssets = assets;
        }
        public String getMime() {
            return mMime;
        }
        public MediaAsset[] getAssets() {
            return mAssets;
        }
        private final String mMime;
        private final MediaAsset[] mAssets;
    }

    private void setUp() throws Exception {
        mHandlerThread = new HandlerThread(TAG);
        mHandlerThread.start();
        mHandler = new Handler(mHandlerThread.getLooper());
        mImageListener = new ImageListener();
    }

    private void tearDown() throws Exception {
        mHandlerThread.quitSafely();
        mHandler = null;
    }

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_main);

        mResources = getResources();

        try {
            setUp();
        }catch (Exception ex){
            Log.e(TAG, ex.getMessage());
        }

    }

    @Override
    protected void onDestroy(){
        super.onDestroy();
        try {
            tearDown();
        }catch (Exception ex){
            Log.e(TAG, ex.getMessage());
        }
    }


    private Decoder[] decoders(MediaAssets assets, boolean goog) {
        String mime = assets.getMime();
        MediaCodecList mcl = new MediaCodecList(MediaCodecList.REGULAR_CODECS);
        ArrayList<Decoder> result = new ArrayList<Decoder>();
        for (MediaCodecInfo info : mcl.getCodecInfos()) {
            if (info.isEncoder()
                    || info.getName().toLowerCase().startsWith("omx.google.") != goog) {
                continue;
            }
            MediaCodecInfo.CodecCapabilities caps = null;
            try {
                caps = info.getCapabilitiesForType(mime);
            } catch (IllegalArgumentException e) { // mime is not supported
                continue;
            }
            assertNotNull(info.getName() + " capabilties for " + mime + " returned null", caps);
            result.add(new Decoder(info.getName(), assets, caps));
        }
        return result.toArray(new Decoder[result.size()]);
    }
    private Decoder[] goog(MediaAssets assets) {
        return decoders(assets, true /* goog */);
    }
    private Decoder[] other(MediaAssets assets) {
        return decoders(assets, false /* goog */);
    }

    /**
     * Validate image based on format and size.
     *
     * @param image The image to be validated.
     * @param width The image width.
     * @param height The image height.
     * @param format The image format.
     * @param filePath The debug dump file path, null if don't want to dump to file.
     */
    public static void validateImage(
            Image image, int width, int height, int format, String filePath) {

        Image.Plane[] imagePlanes = image.getPlanes();
        Log.v(TAG, "Image " + filePath + " Info:");
        Log.v(TAG, "first plane pixelstride " + imagePlanes[0].getPixelStride());
        Log.v(TAG, "first plane rowstride " + imagePlanes[0].getRowStride());
        Log.v(TAG, "Image timestamp:" + image.getTimestamp());

        assertNotNull("Input image is invalid", image);
        assertEquals("Format doesn't match", format, image.getFormat());
        assertEquals("Width doesn't match", width, image.getCropRect().width());
        assertEquals("Height doesn't match", height, image.getCropRect().height());

        Log.v(TAG, "validating Image");

        byte[] data = getDataFromImage(image);
        assertTrue("Invalid image data", data != null && data.length > 0);
        validateYuvData(data, width, height, format, image.getTimestamp());
        if (filePath != null) {
            dumpFile(filePath, data);
        }
    }

    private static void validateYuvData(byte[] yuvData, int width, int height, int format,
                                        long ts) {
        assertTrue("YUV format must be one of the YUV_420_888, NV21, or YV12",
                format == ImageFormat.YUV_420_888 ||
                        format == ImageFormat.NV21 ||
                        format == ImageFormat.YV12);
        Log.v(TAG, "Validating YUV data");
        int expectedSize = width * height * ImageFormat.getBitsPerPixel(format) / 8;
        assertEquals("Yuv data doesn't match", expectedSize, yuvData.length);
    }

    private static void checkYuvFormat(int format) {
        if ((format != ImageFormat.YUV_420_888) &&
                (format != ImageFormat.NV21) &&
                (format != ImageFormat.YV12)) {
            fail("Wrong formats: " + format);
        }
    }

    /**
     * <p>Check android image format validity for an image, only support below formats:</p>
     *
     * <p>Valid formats are YUV_420_888/NV21/YV12 for video decoder</p>
     */
    private static void checkAndroidImageFormat(Image image) {
        int format = image.getFormat();
        Image.Plane[] planes = image.getPlanes();
        switch (format) {
            case ImageFormat.YUV_420_888:
            case ImageFormat.NV21:
            case ImageFormat.YV12:
                assertEquals("YUV420 format Images should have 3 planes", 3, planes.length);
                break;
            default:
                fail("Unsupported Image Format: " + format);
        }
    }


    private static class ImageListener implements ImageReader.OnImageAvailableListener {
        private final LinkedBlockingQueue<Image> mQueue =
                new LinkedBlockingQueue<Image>();
        @Override
        public void onImageAvailable(ImageReader reader) {
            try {
                mQueue.put(reader.acquireNextImage());
            } catch (InterruptedException e) {
                throw new UnsupportedOperationException(
                        "Can't handle InterruptedException in onImageAvailable");
            }
        }
        /**
         * Get an image from the image reader.
         *
         * @param timeout Timeout value for the wait.
         * @return The image from the image reader.
         */
        public Image getImage(long timeout) throws InterruptedException {
            Image image = mQueue.poll(timeout, TimeUnit.MILLISECONDS);
            assertNotNull("Wait for an image timed out in " + timeout + "ms", image);
            return image;
        }
    }

    /**
     * Decode video frames to image reader.
     */
    private void decodeFramesToImage(
            MediaCodec decoder, MediaExtractor extractor, MediaFormat mediaFormat,
            int width, int height, int imageFormat, int mode, boolean checkSwirl)
            throws InterruptedException {
        ByteBuffer[] decoderInputBuffers;
        ByteBuffer[] decoderOutputBuffers;
        // Configure decoder.
        Log.v(TAG, "stream format: " + mediaFormat);
        if (mode == MODE_IMAGEREADER) {
            createImageReader(width, height, imageFormat, MAX_NUM_IMAGES, mImageListener);
            decoder.configure(mediaFormat, mReaderSurface, null /* crypto */, 0 /* flags */);
        } else {
            assertEquals(mode, MODE_IMAGE);
            decoder.configure(mediaFormat, null /* surface */, null /* crypto */, 0 /* flags */);
        }
        decoder.start();
        decoderInputBuffers = decoder.getInputBuffers();
        decoderOutputBuffers = decoder.getOutputBuffers();
        extractor.selectTrack(0);
        // Start decoding and get Image, only test the first NUM_FRAME_DECODED frames.
        MediaCodec.BufferInfo info = new MediaCodec.BufferInfo();
        boolean sawInputEOS = false;
        boolean sawOutputEOS = false;
        int outputFrameCount = 0;
        while (!sawOutputEOS && outputFrameCount < NUM_FRAME_DECODED) {
            Log.v(TAG, "loop:" + outputFrameCount);
            // Feed input frame.
            if (!sawInputEOS) {
                int inputBufIndex = decoder.dequeueInputBuffer(DEFAULT_TIMEOUT_US);
                if (inputBufIndex >= 0) {
                    ByteBuffer dstBuf = decoderInputBuffers[inputBufIndex];
                    int sampleSize =
                            extractor.readSampleData(dstBuf, 0 /* offset */);
                    Log.v(TAG, "queue a input buffer, idx/size: "
                            + inputBufIndex + "/" + sampleSize);
                    long presentationTimeUs = 0;
                    if (sampleSize < 0) {
                        Log.v(TAG, "saw input EOS.");
                        sawInputEOS = true;
                        sampleSize = 0;
                    } else {
                        presentationTimeUs = extractor.getSampleTime();
                    }
                    decoder.queueInputBuffer(
                            inputBufIndex,
                            0 /* offset */,
                            sampleSize,
                            presentationTimeUs,
                            sawInputEOS ? MediaCodec.BUFFER_FLAG_END_OF_STREAM : 0);
                    if (!sawInputEOS) {
                        extractor.advance();
                    }
                }
            }
            // Get output frame
            int res = decoder.dequeueOutputBuffer(info, DEFAULT_TIMEOUT_US);
            Log.v(TAG, "got a buffer: " + info.size + "/" + res);
            if (res == MediaCodec.INFO_TRY_AGAIN_LATER) {
                // no output available yet
                Log.v(TAG, "no output frame available");
            } else if (res == MediaCodec.INFO_OUTPUT_BUFFERS_CHANGED) {
                // decoder output buffers changed, need update.
                Log.v(TAG, "decoder output buffers changed");
                decoderOutputBuffers = decoder.getOutputBuffers();
            } else if (res == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED) {
                // this happens before the first frame is returned.
                MediaFormat outFormat = decoder.getOutputFormat();
                Log.v(TAG, "decoder output format changed: " + outFormat);
            } else if (res < 0) {
                // Should be decoding error.
                fail("unexpected result from deocder.dequeueOutputBuffer: " + res);
            } else {
                if ((info.flags & MediaCodec.BUFFER_FLAG_END_OF_STREAM) != 0) {
                    sawOutputEOS = true;
                }
                // res >= 0: normal decoding case, copy the output buffer.
                // Will use it as reference to valid the ImageReader output
                // Some decoders output a 0-sized buffer at the end. Ignore those.
                boolean doRender = (info.size != 0);
                if (doRender) {
                    outputFrameCount++;
                    Image image = null;
                    try {
                        if (mode == MODE_IMAGE) {
                            image = decoder.getOutputImage(res);
                        } else {
                            decoder.releaseOutputBuffer(res, doRender);
                            res = -1;
                            // Read image and verify
                            image = mImageListener.getImage(WAIT_FOR_IMAGE_TIMEOUT_MS);
                        }

                    } finally {
                        if (image != null) {
                            image.close();
                        }
                    }
                }
                if (res >= 0) {
                    decoder.releaseOutputBuffer(res, false /* render */);
                }
            }
        }
    }

    /**
     * Close the pending images then close current active {@link ImageReader} object.
     */
    private void closeImageReader() {
        if (mReader != null) {
            try {
                // Close all possible pending images first.
                Image image = mReader.acquireLatestImage();
                if (image != null) {
                    image.close();
                }
            } finally {
                mReader.close();
                mReader = null;
            }
        }
    }


    /**
     * Get a byte array image data from an Image object.
     * <p>
     * Read data from all planes of an Image into a contiguous unpadded,
     * unpacked 1-D linear byte array, such that it can be write into disk, or
     * accessed by software conveniently. It supports YUV_420_888/NV21/YV12
     * input Image format.
     * </p>
     * <p>
     * For YUV_420_888/NV21/YV12/Y8/Y16, it returns a byte array that contains
     * the Y plane data first, followed by U(Cb), V(Cr) planes if there is any
     * (xstride = width, ystride = height for chroma and luma components).
     * </p>
     */
    private static byte[] getDataFromImage(Image image) {
        assertNotNull("Invalid image:", image);
        Rect crop = image.getCropRect();
        int format = image.getFormat();
        int width = crop.width();
        int height = crop.height();
        int rowStride, pixelStride;
        byte[] data = null;
        // Read image data
        Image.Plane[] planes = image.getPlanes();
        assertTrue("Fail to get image planes", planes != null && planes.length > 0);
        // Check image validity
        checkAndroidImageFormat(image);
        ByteBuffer buffer = null;
        int offset = 0;
        data = new byte[width * height * ImageFormat.getBitsPerPixel(format) / 8];
        byte[] rowData = new byte[planes[0].getRowStride()];

        Log.v(TAG, "get data from " + planes.length + " planes");

        for (int i = 0; i < planes.length; i++) {
            int shift = (i == 0) ? 0 : 1;
            buffer = planes[i].getBuffer();
            assertNotNull("Fail to get bytebuffer from plane", buffer);
            rowStride = planes[i].getRowStride();
            pixelStride = planes[i].getPixelStride();
            assertTrue("pixel stride " + pixelStride + " is invalid", pixelStride > 0);

            Log.v(TAG, "pixelStride " + pixelStride);
            Log.v(TAG, "rowStride " + rowStride);
            Log.v(TAG, "width " + width);
            Log.v(TAG, "height " + height);

            // For multi-planar yuv images, assuming yuv420 with 2x2 chroma subsampling.
            int w = crop.width() >> shift;
            int h = crop.height() >> shift;
            buffer.position(rowStride * (crop.top >> shift) + pixelStride * (crop.left >> shift));
            assertTrue("rowStride " + rowStride + " should be >= width " + w , rowStride >= w);
            for (int row = 0; row < h; row++) {
                int bytesPerPixel = ImageFormat.getBitsPerPixel(format) / 8;
                int length;
                if (pixelStride == bytesPerPixel) {
                    // Special case: optimized read of the entire row
                    length = w * bytesPerPixel;
                    buffer.get(data, offset, length);
                    offset += length;
                } else {
                    // Generic case: should work for any pixelStride but slower.
                    // Use intermediate buffer to avoid read byte-by-byte from
                    // DirectByteBuffer, which is very bad for performance
                    length = (w - 1) * pixelStride + bytesPerPixel;
                    buffer.get(rowData, 0, length);
                    for (int col = 0; col < w; col++) {
                        data[offset++] = rowData[col * pixelStride];
                    }
                }
                // Advance buffer the remainder of the row stride
                if (row < h - 1) {
                    buffer.position(buffer.position() + rowStride - length);
                }
            }

            Log.v(TAG, "Finished reading data from plane " + i);
        }
        return data;
    }

    private static void dumpFile(String fileName, byte[] data) {
        assertNotNull("fileName must not be null", fileName);
        assertNotNull("data must not be null", data);
        FileOutputStream outStream;
        try {
            Log.v(TAG, "output will be saved as " + fileName);
            outStream = new FileOutputStream(fileName);
        } catch (IOException ioe) {
            throw new RuntimeException("Unable to create debug output file " + fileName, ioe);
        }
        try {
            outStream.write(data);
            outStream.close();
        } catch (IOException ioe) {
            throw new RuntimeException("failed writing data to file " + fileName, ioe);
        }
    }

    private void createImageReader(
            int width, int height, int format, int maxNumImages,
            ImageReader.OnImageAvailableListener listener)  {
        closeImageReader();
        mReader = ImageReader.newInstance(width, height, format, maxNumImages);
        mReaderSurface = mReader.getSurface();
        mReader.setOnImageAvailableListener(listener, mHandler);
        Log.v(TAG, String.format("Created ImageReader size (%dx%d), format %d", width, height,
            format));

    }


    /* Decoder Class */
    class Decoder {
        final private String mName;
        final private String mMime;
        final private MediaCodecInfo.VideoCapabilities mCaps;
        final private ArrayList<MediaAsset> mAssets;
        boolean isFlexibleFormatSupported(MediaCodecInfo.CodecCapabilities caps) {
            for (int c : caps.colorFormats) {
                if (c == COLOR_FormatYUV420Flexible) {
                    return true;
                }
            }
            return false;
        }
        Decoder(String name, MediaAssets assets, MediaCodecInfo.CodecCapabilities caps) {
            mName = name;
            mMime = assets.getMime();
            mCaps = caps.getVideoCapabilities();
            mAssets = new ArrayList<MediaAsset>();
            for (MediaAsset asset : assets.getAssets()) {
                if (mCaps.areSizeAndRateSupported(asset.getWidth(), asset.getHeight(), SWIRL_FPS)
                        && isFlexibleFormatSupported(caps)) {
                    mAssets.add(asset);
                }
            }
        }
        public boolean videoDecode(int mode, boolean checkSwirl) {
            boolean skipped = true;
            for (MediaAsset asset: mAssets) {
                // TODO: loop over all supported image formats
                int imageFormat = ImageFormat.YUV_420_888;
                int colorFormat = COLOR_FormatYUV420Flexible;
                videoDecode(asset, imageFormat, colorFormat, mode, checkSwirl);
                skipped = false;
            }
            return skipped;
        }
        private void videoDecode(
                MediaAsset asset, int imageFormat, int colorFormat, int mode, boolean checkSwirl) {
            int video = asset.getResource();
            int width = asset.getWidth();
            int height = asset.getHeight();
            Log.d(TAG, "videoDecode " + mName + " " + width + "x" + height);
            MediaCodec decoder = null;
            AssetFileDescriptor vidFD = null;
            MediaExtractor extractor = null;
            File tmpFile = null;
            InputStream is = null;
            FileOutputStream os = null;
            MediaFormat mediaFormat = null;
            try {
                extractor = new MediaExtractor();
                try {
                    vidFD = mResources.openRawResourceFd(video);
                    extractor.setDataSource(
                            vidFD.getFileDescriptor(), vidFD.getStartOffset(), vidFD.getLength());
                } catch (Resources.NotFoundException e) {
                    // resource is compressed, uncompress locally
                    String tmpName = "tempStream";
                    tmpFile = File.createTempFile(tmpName, null, getApplication().getCacheDir());
                    is = mResources.openRawResource(video);
                    os = new FileOutputStream(tmpFile);
                    byte[] buf = new byte[1024];
                    int len;
                    while ((len = is.read(buf, 0, buf.length)) > 0) {
                        os.write(buf, 0, len);
                    }
                    os.close();
                    is.close();
                    extractor.setDataSource(tmpFile.getAbsolutePath());
                }
                mediaFormat = extractor.getTrackFormat(0);
                mediaFormat.setInteger(MediaFormat.KEY_COLOR_FORMAT, colorFormat);
                // Create decoder
                decoder = MediaCodec.createByCodecName(mName);
                assertNotNull("couldn't create decoder" + mName, decoder);
                decodeFramesToImage(
                        decoder, extractor, mediaFormat,
                        width, height, imageFormat, mode, checkSwirl);
                decoder.stop();
                if (vidFD != null) {
                    vidFD.close();
                }
            } catch (Throwable e) {
                throw new RuntimeException("while " + mName + " decoding "
                        + mResources.getResourceEntryName(video) + ": " + mediaFormat, e);
            } finally {
                if (decoder != null) {
                    decoder.release();
                }
                if (extractor != null) {
                    extractor.release();
                }
                if (tmpFile != null) {
                    tmpFile.delete();
                }
            }
        }
    }
}
