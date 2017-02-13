/**
 * Copyright (c) 2015-present, Facebook, Inc.
 * All rights reserved.
 *
 * This source code is licensed under the BSD-style license found in the
 * LICENSE file in the root directory of this source tree. An additional grant
 * of patent rights can be found in the PATENTS file in the same directory.
 */

package com.facebook.react.modules.camera;

import javax.annotation.Nullable;

import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.nio.channels.FileChannel;
import java.util.ArrayList;
import java.util.List;

import android.content.ContentResolver;
import android.content.Context;
import android.content.res.AssetFileDescriptor;
import android.database.Cursor;
import android.graphics.BitmapFactory;
import android.media.MediaScannerConnection;
import android.net.Uri;
import android.os.AsyncTask;
import android.os.Build;
import android.os.Environment;
import android.provider.MediaStore;
import android.provider.MediaStore.Images;
import android.text.TextUtils;

import com.facebook.common.logging.FLog;
import com.facebook.react.bridge.GuardedAsyncTask;
import com.facebook.react.bridge.Promise;
import com.facebook.react.bridge.ReactApplicationContext;
import com.facebook.react.bridge.ReactContext;
import com.facebook.react.bridge.ReactContextBaseJavaModule;
import com.facebook.react.bridge.ReactMethod;
import com.facebook.react.bridge.JSApplicationIllegalArgumentException;
import com.facebook.react.bridge.NativeModule;
import com.facebook.react.bridge.ReadableArray;
import com.facebook.react.bridge.ReadableMap;
import com.facebook.react.bridge.WritableArray;
import com.facebook.react.bridge.WritableMap;
import com.facebook.react.bridge.WritableNativeArray;
import com.facebook.react.bridge.WritableNativeMap;
import com.facebook.react.common.ReactConstants;
import com.facebook.react.module.annotations.ReactModule;

// TODO #6015104: rename to something less iOSish
/**
 * {@link NativeModule} that allows JS to interact with the photos on the device (i.e.
 * {@link MediaStore.Images}).
 */
@ReactModule(name = "RKCameraRollManager")
public class CameraRollManager extends ReactContextBaseJavaModule {

  private static final String ERROR_UNABLE_TO_LOAD = "E_UNABLE_TO_LOAD";
  private static final String ERROR_UNABLE_TO_LOAD_PERMISSION = "E_UNABLE_TO_LOAD_PERMISSION";
  private static final String ERROR_UNABLE_TO_SAVE = "E_UNABLE_TO_SAVE";

  public static final boolean IS_JELLY_BEAN_OR_LATER =
      Build.VERSION.SDK_INT >= Build.VERSION_CODES.JELLY_BEAN;

  private static final String[] PROJECTION;
  static {
    if (IS_JELLY_BEAN_OR_LATER) {
      PROJECTION = new String[] {
          Images.Media._ID,
          Images.Media.MIME_TYPE,
          Images.Media.BUCKET_DISPLAY_NAME,
          Images.Media.DATE_TAKEN,
          Images.Media.WIDTH,
          Images.Media.HEIGHT,
          Images.Media.LONGITUDE,
          Images.Media.LATITUDE,
          Images.Media.DISPLAY_NAME
      };
    } else {
      PROJECTION = new String[] {
          Images.Media._ID,
          Images.Media.MIME_TYPE,
          Images.Media.BUCKET_DISPLAY_NAME,
          Images.Media.DATE_TAKEN,
          Images.Media.LONGITUDE,
          Images.Media.LATITUDE,
          Images.Media.DISPLAY_NAME
      };
    }
  }

  private static final String SELECTION_BUCKET = Images.Media.BUCKET_DISPLAY_NAME + " = ?";
  private static final String SELECTION_DATE_TAKEN = Images.Media.DATE_TAKEN + " < ?";

  public CameraRollManager(ReactApplicationContext reactContext) {
    super(reactContext);
  }

  @Override
  public String getName() {
    return "RKCameraRollManager";
  }

  /**
   * Save an image to the gallery (i.e. {@link MediaStore.Images}). This copies the original file
   * from wherever it may be to the external storage pictures directory, so that it can be scanned
   * by the MediaScanner.
   *
   * @param uri the file:// URI of the image to save
   * @param promise to be resolved or rejected
   */
  @ReactMethod
  public void saveToCameraRoll(String uri, String type, Promise promise) {
    MediaType parsedType = type.equals("video") ? MediaType.VIDEO : MediaType.PHOTO;
    new SaveToCameraRoll(getReactApplicationContext(), Uri.parse(uri), parsedType, promise)
        .executeOnExecutor(AsyncTask.THREAD_POOL_EXECUTOR);
  }

  private enum MediaType { PHOTO, VIDEO };
  private static class SaveToCameraRoll extends GuardedAsyncTask<Void, Void> {

    private final Context mContext;
    private final Uri mUri;
    private final Promise mPromise;
    private final MediaType mType;

    public SaveToCameraRoll(ReactContext context, Uri uri, MediaType type, Promise promise) {
      super(context);
      mContext = context;
      mUri = uri;
      mPromise = promise;
      mType = type;
    }

    @Override
    protected void doInBackgroundGuarded(Void... params) {
      File source = new File(mUri.getPath());
      FileChannel input = null, output = null;
      try {
        File exportDir = (mType == MediaType.PHOTO)
          ? Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_PICTURES)
          : Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_MOVIES);
        exportDir.mkdirs();
        if (!exportDir.isDirectory()) {
          mPromise.reject(ERROR_UNABLE_TO_LOAD, "External media storage directory not available");
          return;
        }
        File dest = new File(exportDir, source.getName());
        int n = 0;
        String fullSourceName = source.getName();
        String sourceName, sourceExt;
        if (fullSourceName.indexOf('.') >= 0) {
          sourceName = fullSourceName.substring(0, fullSourceName.lastIndexOf('.'));
          sourceExt = fullSourceName.substring(fullSourceName.lastIndexOf('.'));
        } else {
          sourceName = fullSourceName;
          sourceExt = "";
        }
        while (!dest.createNewFile()) {
          dest = new File(exportDir, sourceName + "_" + (n++) + sourceExt);
        }
        input = new FileInputStream(source).getChannel();
        output = new FileOutputStream(dest).getChannel();
        output.transferFrom(input, 0, input.size());
        input.close();
        output.close();

        MediaScannerConnection.scanFile(
            mContext,
            new String[]{dest.getAbsolutePath()},
            null,
            new MediaScannerConnection.OnScanCompletedListener() {
              @Override
              public void onScanCompleted(String path, Uri uri) {
                if (uri != null) {
                  mPromise.resolve(uri.toString());
                } else {
                  mPromise.reject(ERROR_UNABLE_TO_SAVE, "Could not add image to gallery");
                }
              }
            });
      } catch (IOException e) {
        mPromise.reject(e);
      } finally {
        if (input != null && input.isOpen()) {
          try {
            input.close();
          } catch (IOException e) {
            FLog.e(ReactConstants.TAG, "Could not close input channel", e);
          }
        }
        if (output != null && output.isOpen()) {
          try {
            output.close();
          } catch (IOException e) {
            FLog.e(ReactConstants.TAG, "Could not close output channel", e);
          }
        }
      }
    }
  }

  /**
   * Get photos from {@link MediaStore.Images}, most recent first.
   *
   * @param params a map containing the following keys:
   *        <ul>
   *          <li>first (mandatory): a number representing the number of photos to fetch</li>
   *          <li>
   *            after (optional): a cursor that matches page_info[end_cursor] returned by a
   *            previous call to {@link #getPhotos}
   *          </li>
   *          <li>groupName (optional): an album name</li>
   *          <li>
   *            mimeType (optional): restrict returned images to a specific mimetype (e.g.
   *            image/jpeg)
   *          </li>
   *        </ul>
   * @param promise the Promise to be resolved when the photos are loaded; for a format of the
   *        parameters passed to this callback, see {@code getPhotosReturnChecker} in CameraRoll.js
   */
  @ReactMethod
  public void getPhotos(final ReadableMap params, final Promise promise) {
    int first = params.getInt("first");
    String after = params.hasKey("after") ? params.getString("after") : null;
    String assetType = params.hasKey("assetType") ? params.getString("assetType") : null;
    String groupName = params.hasKey("groupName") ? params.getString("groupName") : null;
    ReadableArray mimeTypes = params.hasKey("mimeTypes")
        ? params.getArray("mimeTypes")
        : null;
    if (params.hasKey("groupTypes")) {
      throw new JSApplicationIllegalArgumentException("groupTypes is not supported on Android");
    }

    new GetPhotosTask(
          getReactApplicationContext(),
          first,
          after,
          assetType,
          groupName,
          mimeTypes,
          promise)
          .executeOnExecutor(AsyncTask.THREAD_POOL_EXECUTOR);
  }

  private static class GetPhotosTask extends GuardedAsyncTask<Void, Void> {
    private final Context mContext;
    private final int mFirst;
    private final @Nullable String mAfter;
    private final @Nullable String mAssetType;
    private final @Nullable String mGroupName;
    private final @Nullable ReadableArray mMimeTypes;
    private final Promise mPromise;

    private GetPhotosTask(
        ReactContext context,
        int first,
        @Nullable String after,
        @Nullable String assetType,
        @Nullable String groupName,
        @Nullable ReadableArray mimeTypes,
        Promise promise) {
      super(context);
      mContext = context;
      mFirst = first;
      mAfter = after;
      mAssetType = assetType;
      mGroupName = groupName;
      mMimeTypes = mimeTypes;
      mPromise = promise;
    }

    @Override
    protected void doInBackgroundGuarded(Void... params) {
      StringBuilder selection = new StringBuilder("1");
      List<String> selectionArgs = new ArrayList<>();
      if (!TextUtils.isEmpty(mAfter)) {
        selection.append(" AND " + SELECTION_DATE_TAKEN);
        selectionArgs.add(mAfter);
      }
      if (!TextUtils.isEmpty(mGroupName)) {
        selection.append(" AND " + SELECTION_BUCKET);
        selectionArgs.add(mGroupName);
      }
      Uri uri;
      if (mAssetType == null || "All".equals(mAssetType)) {
        uri = MediaStore.Files.getContentUri("external");
        if (mMimeTypes == null || mMimeTypes.size() == 0) {
          selection.append(" AND (" + Images.Media.MIME_TYPE + " LIKE ? OR "
                  + Images.Media.MIME_TYPE + " LIKE ?) ");
          selectionArgs.add("image/%");
          selectionArgs.add("video/%");
        }
      } else if ("Photos".equals(mAssetType)) {
        uri = Images.Media.EXTERNAL_CONTENT_URI;
        if (mMimeTypes == null || mMimeTypes.size() == 0) {
          selection.append(" AND " + Images.Media.MIME_TYPE + " LIKE ? ");
          selectionArgs.add("image/%");
        }
      } else {
        uri = MediaStore.Video.Media.EXTERNAL_CONTENT_URI;
        if (mMimeTypes == null || mMimeTypes.size() == 0) {
          selection.append(" AND " + Images.Media.MIME_TYPE + " LIKE ? ");
          selectionArgs.add("video/%");
        }
      }

      if (mMimeTypes != null && mMimeTypes.size() > 0) {
        selection.append(" AND " + Images.Media.MIME_TYPE + " IN (");
        for (int i = 0; i < mMimeTypes.size(); i++) {
          selection.append("?,");
          selectionArgs.add(mMimeTypes.getString(i));
        }
        selection.replace(selection.length() - 1, selection.length(), ")");
      }
      WritableMap response = new WritableNativeMap();
      ContentResolver resolver = mContext.getContentResolver();
      // using LIMIT in the sortOrder is not explicitly supported by the SDK (which does not support
      // setting a limit at all), but it works because this specific ContentProvider is backed by
      // an SQLite DB and forwards parameters to it without doing any parsing / validation.
      try {
        Cursor assets = resolver.query(
            uri,
            PROJECTION,
            selection.toString(),
            selectionArgs.toArray(new String[selectionArgs.size()]),
            Images.Media.DATE_TAKEN + " DESC, " + Images.Media.DATE_MODIFIED + " DESC LIMIT " +
                (mFirst + 1)); // set LIMIT to first + 1 so that we know how to populate page_info
        if (assets == null) {
          mPromise.reject(ERROR_UNABLE_TO_LOAD, "Could not get assets");
        } else {
          try {
            putEdges(resolver, assets, response, mFirst);
            putPageInfo(assets, response, mFirst);
          } finally {
            assets.close();
            mPromise.resolve(response);
          }
        }
      } catch (SecurityException e) {
        mPromise.reject(
            ERROR_UNABLE_TO_LOAD_PERMISSION,
            "Could not get assets: need READ_EXTERNAL_STORAGE permission",
            e);
      }
    }
  }

  private static void putPageInfo(Cursor photos, WritableMap response, int limit) {
    WritableMap pageInfo = new WritableNativeMap();
    pageInfo.putBoolean("has_next_page", limit < photos.getCount());
    if (limit < photos.getCount()) {
      photos.moveToPosition(limit - 1);
      pageInfo.putString(
          "end_cursor",
          photos.getString(photos.getColumnIndex(Images.Media.DATE_TAKEN)));
    }
    response.putMap("page_info", pageInfo);
  }

  private static void putEdges(
      ContentResolver resolver,
      Cursor assets,
      WritableMap response,
      int limit) {
    WritableArray edges = new WritableNativeArray();
    assets.moveToFirst();
    int idIndex = assets.getColumnIndex(Images.Media._ID);
    int mimeTypeIndex = assets.getColumnIndex(Images.Media.MIME_TYPE);
    int groupNameIndex = assets.getColumnIndex(Images.Media.BUCKET_DISPLAY_NAME);
    int dateTakenIndex = assets.getColumnIndex(Images.Media.DATE_TAKEN);
    int widthIndex = IS_JELLY_BEAN_OR_LATER ? assets.getColumnIndex(Images.Media.WIDTH) : -1;
    int heightIndex = IS_JELLY_BEAN_OR_LATER ? assets.getColumnIndex(Images.Media.HEIGHT) : -1;
    int longitudeIndex = assets.getColumnIndex(Images.Media.LONGITUDE);
    int latitudeIndex = assets.getColumnIndex(Images.Media.LATITUDE);
    int filenameIndex = assets.getColumnIndex(Images.Media.DISPLAY_NAME);

    for (int i = 0; i < limit && !assets.isAfterLast(); i++) {
      WritableMap edge = new WritableNativeMap();
      WritableMap node = new WritableNativeMap();
      String mimeType = assets.getString(mimeTypeIndex);

      boolean assetSuccess;
      if (mimeType.startsWith("image/")) {
        assetSuccess = putImageInfo(resolver, assets, node, idIndex, widthIndex, heightIndex,
                filenameIndex);
      } else {
        assetSuccess = putVideoInfo(resolver, assets, node, idIndex, widthIndex, heightIndex,
                filenameIndex);
      }

      if (assetSuccess) {
        putBasicNodeInfo(assets, node, mimeType, groupNameIndex, dateTakenIndex);
        putLocationInfo(assets, node, longitudeIndex, latitudeIndex);

        edge.putMap("node", node);
        edges.pushMap(edge);
      } else {
        // we skipped an image because we couldn't get its details (e.g. width/height), so we
        // decrement i in order to correctly reach the limit, if the cursor has enough rows
        i--;
      }
      assets.moveToNext();
    }
    response.putArray("edges", edges);
  }

  private static void putBasicNodeInfo(
      Cursor assets,
      WritableMap node,
      String mimeType,
      int groupNameIndex,
      int dateTakenIndex) {
    node.putString("type", mimeType);
    node.putString("group_name", assets.getString(groupNameIndex));
    node.putDouble("timestamp", assets.getLong(dateTakenIndex) / 1000d);
  }

  private static boolean putImageInfo(
      ContentResolver resolver,
      Cursor assets,
      WritableMap node,
      int idIndex,
      int widthIndex,
      int heightIndex,
      int filenameIndex) {
    WritableMap image = new WritableNativeMap();
    Uri photoUri = Uri.withAppendedPath(
        Images.Media.EXTERNAL_CONTENT_URI,
            assets.getString(idIndex));
    image.putString("uri", photoUri.toString());
    float width = -1;
    float height = -1;
    if (IS_JELLY_BEAN_OR_LATER) {
      width = assets.getInt(widthIndex);
      height = assets.getInt(heightIndex);
    }
    if (width <= 0 || height <= 0) {
      try {
        AssetFileDescriptor photoDescriptor = resolver.openAssetFileDescriptor(photoUri, "r");
        BitmapFactory.Options options = new BitmapFactory.Options();
        // Set inJustDecodeBounds to true so we don't actually load the Bitmap, but only get its
        // dimensions instead.
        options.inJustDecodeBounds = true;
        BitmapFactory.decodeFileDescriptor(photoDescriptor.getFileDescriptor(), null, options);
        photoDescriptor.close();

        width = options.outWidth;
        height = options.outHeight;
      } catch (IOException e) {
        FLog.e(ReactConstants.TAG, "Could not get width/height for " + photoUri.toString(), e);
        return false;
      }
    }
    image.putDouble("width", width);
    image.putDouble("height", height);
    image.putString("filename", assets.getString(filenameIndex));
    node.putMap("image", image);

    return true;
  }

  private static boolean putVideoInfo(
          ContentResolver resolver,
          Cursor assets,
          WritableMap node,
          int idIndex,
          int widthIndex,
          int heightIndex,
          int filenameIndex) {
    WritableMap video = new WritableNativeMap();
    String id = assets.getString(idIndex);
    Uri videoUri = Uri.withAppendedPath(MediaStore.Video.Media.EXTERNAL_CONTENT_URI, id);
    video.putString("uri", videoUri.toString());

    float width = -1;
    float height = -1;
    if (IS_JELLY_BEAN_OR_LATER) {
      width = assets.getInt(widthIndex);
      height = assets.getInt(heightIndex);
    }
    video.putDouble("width", width);
    video.putDouble("height", height);
    video.putString("filename", assets.getString(filenameIndex));
    node.putMap("video", video);

    return true;
  }

  private static void putLocationInfo(
      Cursor assets,
      WritableMap node,
      int longitudeIndex,
      int latitudeIndex) {
    double longitude = assets.getDouble(longitudeIndex);
    double latitude = assets.getDouble(latitudeIndex);
    if (longitude > 0 || latitude > 0) {
      WritableMap location = new WritableNativeMap();
      location.putDouble("longitude", longitude);
      location.putDouble("latitude", latitude);
      node.putMap("location", location);
    }
  }
}
