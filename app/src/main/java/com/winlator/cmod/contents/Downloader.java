/**
 * Shared download helper with WinNative.dev-first resolution.
 *
 * On first use within a session the class recursively crawls
 * https://WinNative.dev/Downloads/ and every subdirectory it finds,
 * building a complete  filename → full-URL  map.  When any download
 * is requested the filename from the original (GitHub) URL is looked
 * up in that map.  If the file exists on WinNative.dev the download
 * is attempted from there first; only if it fails does it fall back
 * to the original URL.
 *
 * The map is rebuilt automatically when clearFileMap() is called
 * (e.g. at the start of a new wizard session).
 */
package com.winlator.cmod.contents;

import android.util.Log;
import androidx.preference.PreferenceManager;
import com.winlator.cmod.PluviaApp;

import java.io.BufferedReader;
import java.io.File;
import java.io.FileOutputStream;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.net.URL;
import java.net.URLConnection;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

public class Downloader {

    private static final String TAG = "Downloader";
    private static final String WINNATIVE_ROOT = "https://WinNative.dev/Downloads/";
    private static final int MAX_CRAWL_DEPTH = 6;

    /** Returns true only if the user has enabled download logging in Debug settings. */
    private static boolean logEnabled() {
        try {
            android.content.Context ctx = PluviaApp.Companion.getInstance().getApplicationContext();
            if (ctx == null) return false;
            return PreferenceManager.getDefaultSharedPreferences(ctx)
                    .getBoolean("enable_download_logs", false);
        } catch (Exception e) {
            return false;
        }
    }

    // ---- Global file map (filename-lowercase → full download URL) ----
    private static final ConcurrentHashMap<String, String> fileMap = new ConcurrentHashMap<>();
    private static volatile boolean fileMapReady = false;
    private static final Object mapLock = new Object();

    // ----------------------------------------------------------------
    //  Public listener
    // ----------------------------------------------------------------
    public interface DownloadListener {
        void onProgress(long downloadedBytes, long totalBytes);
    }

    // ----------------------------------------------------------------
    //  WinNative-first download  (primary API)
    // ----------------------------------------------------------------

    /**
     * Downloads a file, trying WinNative.dev first for the same filename,
     * falling back to the original URL if WinNative.dev fails or does not
     * host the file.
     *
     * @param originalUrl  The original (typically GitHub) download URL.
     * @param file         Destination file.
     * @param listener     Optional progress callback.
     * @return true if the file was downloaded successfully from either source.
     */
    public static boolean downloadFileWinNativeFirst(String originalUrl, File file, DownloadListener listener) {
        String filename = extractFilename(originalUrl);
        if (filename != null) {
            ensureFileMap();
            String winUrl = fileMap.get(filename.toLowerCase(Locale.ROOT));
            if (winUrl != null) {
                if (logEnabled()) Log.d(TAG, "WinNative URL resolved: " + winUrl);
                if (downloadFile(winUrl, file, listener)) {
                    if (logEnabled()) Log.d(TAG, "Download succeeded from WinNative.dev");
                    return true;
                }
                if (logEnabled()) Log.w(TAG, "WinNative download failed, falling back to: " + originalUrl);
                file.delete();
            } else {
                if (logEnabled()) Log.d(TAG, "File not found on WinNative.dev, using original: " + originalUrl);
            }
        }
        return downloadFile(originalUrl, file, listener);
    }

    /**
     * Legacy wrapper – delegates to {@link #downloadFileWinNativeFirst}.
     * Kept for call-sites that still pass a contentType (ignored now).
     */
    public static boolean downloadFileWithFallback(String contentType, String originalUrl, File file, DownloadListener listener) {
        return downloadFileWinNativeFirst(originalUrl, file, listener);
    }

    // ----------------------------------------------------------------
    //  File-map build / lookup
    // ----------------------------------------------------------------

    /**
     * Ensures the file map has been built at least once this session.
     * Safe to call from any thread; only the first caller actually crawls.
     */
    public static void ensureFileMap() {
        if (fileMapReady) return;
        synchronized (mapLock) {
            if (fileMapReady) return;
            buildFileMap();
            fileMapReady = true;
        }
    }

    /**
     * Forces a fresh crawl of WinNative.dev/Downloads/ on next access.
     * Call at the start of a wizard session so newly-uploaded files are found.
     */
    public static void clearFileMap() {
        synchronized (mapLock) {
            fileMap.clear();
            fileMapReady = false;
        }
    }

    /** @deprecated Use {@link #clearFileMap()} instead. */
    @Deprecated
    public static void clearDirectoryCache() {
        clearFileMap();
    }

    /**
     * Returns the resolved WinNative URL for a given filename, or null if not hosted.
     */
    public static String resolveWinNativeUrl(String filename) {
        if (filename == null) return null;
        ensureFileMap();
        return fileMap.get(filename.toLowerCase(Locale.ROOT));
    }

    /** Variant that accepts (and ignores) a contentType for back-compat. */
    public static String resolveWinNativeUrl(String contentType, String filename) {
        return resolveWinNativeUrl(filename);
    }

    // ----------------------------------------------------------------
    //  Recursive directory crawler
    // ----------------------------------------------------------------

    /**
     * Recursively crawls {@link #WINNATIVE_ROOT} and populates {@link #fileMap}
     * with every downloadable file found (filename-lowercase → full URL).
     */
    private static void buildFileMap() {
        if (logEnabled()) Log.d(TAG, "Building WinNative file map from " + WINNATIVE_ROOT);
        long start = System.currentTimeMillis();
        fileMap.clear();
        crawlDirectory(WINNATIVE_ROOT, 0);
        if (logEnabled()) Log.d(TAG, "WinNative file map built: " + fileMap.size() + " files in " +
                (System.currentTimeMillis() - start) + "ms");
    }

    /**
     * Fetches a single directory listing page, adds any file entries to the map,
     * and recurses into subdirectories up to {@link #MAX_CRAWL_DEPTH}.
     */
    private static void crawlDirectory(String dirUrl, int depth) {
        if (depth > MAX_CRAWL_DEPTH) return;

        String html;
        try {
            html = downloadString(dirUrl);
        } catch (Exception e) {
            if (logEnabled()) Log.w(TAG, "Crawl failed for " + dirUrl + ": " + e.getMessage());
            return;
        }
        if (html == null) return;

        // Parse href entries from Apache-style "Index of" listing
        Pattern pattern = Pattern.compile("href=\"([^\"?]+)\"");
        Matcher matcher = pattern.matcher(html);
        List<String> subdirs = new ArrayList<>();

        while (matcher.find()) {
            String href = matcher.group(1);
            // Skip absolute paths, parent directory, and sorting query links
            if (href.startsWith("/") || href.startsWith("..") || href.startsWith("?") || href.startsWith("http")) {
                continue;
            }

            if (href.endsWith("/")) {
                // It's a subdirectory – queue for recursive crawl
                subdirs.add(dirUrl + href);
            } else {
                // It's a file – add to the map (lowercase key for case-insensitive lookup)
                String key = href.toLowerCase(Locale.ROOT);
                String fullUrl = dirUrl + href;
                // If duplicate filename across folders, keep the first one found
                // (closest to root = most likely the canonical location)
                fileMap.putIfAbsent(key, fullUrl);
            }
        }

        // Recurse into subdirectories
        for (String subdir : subdirs) {
            crawlDirectory(subdir, depth + 1);
        }
    }

    // ----------------------------------------------------------------
    //  Core download methods
    // ----------------------------------------------------------------

    /**
     * Extracts the filename (last path segment) from a URL.
     */
    public static String extractFilename(String url) {
        if (url == null) return null;
        int lastSlash = url.lastIndexOf('/');
        if (lastSlash >= 0 && lastSlash < url.length() - 1) {
            return url.substring(lastSlash + 1);
        }
        return null;
    }

    /**
     * Downloads a file from the given address with progress reporting.
     */
    public static boolean downloadFile(String address, File file, DownloadListener listener) {
        try {
            URL url = new URL(address);
            URLConnection connection = url.openConnection();
            connection.setRequestProperty("Accept-Encoding", "identity");
            connection.setConnectTimeout(15000);
            connection.setReadTimeout(30000);
            connection.connect();

            InputStream input = url.openStream();
            OutputStream output = new FileOutputStream(file.getAbsolutePath());

            byte[] data = new byte[8192];
            int count;
            long total = 0;
            long lengthOfFile = connection.getContentLengthLong();
            long lastUpdateTime = 0;

            while ((count = input.read(data)) != -1) {
                total += count;
                output.write(data, 0, count);
                if (listener != null) {
                    long currentTime = System.currentTimeMillis();
                    if (currentTime - lastUpdateTime > 80 || total == lengthOfFile) {
                        listener.onProgress(total, lengthOfFile);
                        lastUpdateTime = currentTime;
                    }
                }
            }

            output.flush();
            output.close();
            input.close();
            return true;
        } catch (Exception e) {
            e.printStackTrace();
            return false;
        }
    }

    /**
     * Downloads a URL as a String (used for JSON fetches and directory listings).
     */
    public static String downloadString(String address) {
        try {
            URL url = new URL(address);
            URLConnection connection = url.openConnection();
            connection.setConnectTimeout(5000);
            connection.setReadTimeout(10000);
            connection.connect();

            InputStream input = url.openStream();
            BufferedReader reader = new BufferedReader(new InputStreamReader(input));
            StringBuilder sb = new StringBuilder();
            String line;
            while ((line = reader.readLine()) != null) {
                sb.append(line).append("\n");
            }
            reader.close();
            return sb.toString();
        } catch (Exception e) {
            e.printStackTrace();
            return null;
        }
    }
}