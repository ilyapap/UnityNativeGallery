package com.yasirkula.unity;

import android.Manifest;
import android.annotation.TargetApi;
import android.app.Activity;
import android.app.Fragment;
import android.content.ContentValues;
import android.content.Context;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.media.ExifInterface;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.os.Environment;
import android.provider.MediaStore;
import android.provider.Settings;
import android.util.Log;
import android.webkit.MimeTypeMap;

import java.io.File;
import java.io.FileOutputStream;
import java.util.Locale;

/**
 * Created by yasirkula on 22.06.2017.
 */

public class NativeGallery
{
	public static final int MEDIA_TYPE_IMAGE = 1;
	public static final int MEDIA_TYPE_VIDEO = 2;
	public static final int MEDIA_TYPE_AUDIO = 4;

	public static String SaveMedia( Context context, int mediaType, String filePath, String directoryName )
	{
		File originalFile = new File( filePath );
		if( !originalFile.exists() )
		{
			Log.e( "Unity", "Original media file is missing or inaccessible!" );
			return "";
		}

		int pathSeparator = filePath.lastIndexOf( '/' );
		int extensionSeparator = filePath.lastIndexOf( '.' );
		String filename = pathSeparator >= 0 ? filePath.substring( pathSeparator + 1 ) : filePath;
		String extension = extensionSeparator >= 0 ? filePath.substring( extensionSeparator + 1 ) : "";

		// Credit: https://stackoverflow.com/a/31691791/2373034
		String mimeType = extension.length() > 0 ? MimeTypeMap.getSingleton().getMimeTypeFromExtension( extension.toLowerCase( Locale.ENGLISH ) ) : null;

		ContentValues values = new ContentValues();
		values.put( MediaStore.MediaColumns.TITLE, filename );
		values.put( MediaStore.MediaColumns.DISPLAY_NAME, filename );
		values.put( MediaStore.MediaColumns.DATE_ADDED, System.currentTimeMillis() / 1000 );

		if( mimeType != null && mimeType.length() > 0 )
			values.put( MediaStore.MediaColumns.MIME_TYPE, mimeType );

		if( mediaType == MEDIA_TYPE_IMAGE )
		{
			int imageOrientation = NativeGalleryUtils.GetImageOrientation( context, filePath );
			switch( imageOrientation )
			{
				case ExifInterface.ORIENTATION_ROTATE_270:
				case ExifInterface.ORIENTATION_TRANSVERSE:
				{
					values.put( MediaStore.Images.Media.ORIENTATION, 270 );
					break;
				}
				case ExifInterface.ORIENTATION_ROTATE_180:
				{
					values.put( MediaStore.Images.Media.ORIENTATION, 180 );
					break;
				}
				case ExifInterface.ORIENTATION_ROTATE_90:
				case ExifInterface.ORIENTATION_TRANSPOSE:
				{
					values.put( MediaStore.Images.Media.ORIENTATION, 90 );
					break;
				}
			}
		}

		Uri externalContentUri;
		if( mediaType == MEDIA_TYPE_IMAGE )
			externalContentUri = MediaStore.Images.Media.EXTERNAL_CONTENT_URI;
		else if( mediaType == MEDIA_TYPE_VIDEO )
			externalContentUri = MediaStore.Video.Media.EXTERNAL_CONTENT_URI;
		else
			externalContentUri = MediaStore.Audio.Media.EXTERNAL_CONTENT_URI;

		// Android 10 restricts our access to the raw filesystem, use MediaStore to save media in that case
		if( android.os.Build.VERSION.SDK_INT >= 29 )
		{
			values.put( MediaStore.MediaColumns.RELATIVE_PATH, "DCIM/" + directoryName );
			values.put( MediaStore.MediaColumns.DATE_TAKEN, System.currentTimeMillis() );
			values.put( MediaStore.MediaColumns.IS_PENDING, true );

			Uri uri = context.getContentResolver().insert( externalContentUri, values );
			if( uri != null )
			{
				try
				{
					if( NativeGalleryUtils.WriteFileToStream( originalFile, context.getContentResolver().openOutputStream( uri ) ) )
					{
						values.put( MediaStore.MediaColumns.IS_PENDING, false );
						context.getContentResolver().update( uri, values, null, null );

						Log.d( "Unity", "Saved media to: " + uri.toString() );

						String path = NativeGalleryUtils.GetPathFromURI( context, uri );
						return path != null && path.length() > 0 ? path : uri.toString();
					}
				}
				catch( Exception e )
				{
					Log.e( "Unity", "Exception:", e );
					context.getContentResolver().delete( uri, null, null );
				}
			}
		}
		else
		{
			File directory = new File( Environment.getExternalStoragePublicDirectory( Environment.DIRECTORY_DCIM ), directoryName );
			directory.mkdirs();

			File file;
			int fileIndex = 1;
			String filenameWithoutExtension = ( extension.length() > 0 && filename.length() > extension.length() ) ? filename.substring( 0, filename.length() - extension.length() - 1 ) : filename;
			String newFilename = filename;
			do
			{
				file = new File( directory, newFilename );
				newFilename = filenameWithoutExtension + fileIndex++;
				if( extension.length() > 0 )
					newFilename += "." + extension;
			} while( file.exists() );

			try
			{
				if( NativeGalleryUtils.WriteFileToStream( originalFile, new FileOutputStream( file ) ) )
				{
					values.put( MediaStore.MediaColumns.DATA, file.getAbsolutePath() );
					context.getContentResolver().insert( externalContentUri, values );

					Log.d( "Unity", "Saved media to: " + file.getPath() );

					// Refresh the Gallery
					Intent mediaScanIntent = new Intent( Intent.ACTION_MEDIA_SCANNER_SCAN_FILE );
					mediaScanIntent.setData( Uri.fromFile( file ) );
					context.sendBroadcast( mediaScanIntent );

					return file.getAbsolutePath();
				}
			}
			catch( Exception e )
			{
				Log.e( "Unity", "Exception:", e );
			}
		}

		return "";
	}

	public static void MediaDeleteFile( Context context, String path, int mediaType )
	{
		if( mediaType == MEDIA_TYPE_IMAGE )
			context.getContentResolver().delete( MediaStore.Images.Media.EXTERNAL_CONTENT_URI, MediaStore.Images.Media.DATA + "=?", new String[] { path } );
		else if( mediaType == MEDIA_TYPE_VIDEO )
			context.getContentResolver().delete( MediaStore.Video.Media.EXTERNAL_CONTENT_URI, MediaStore.Video.Media.DATA + "=?", new String[] { path } );
		else
			context.getContentResolver().delete( MediaStore.Audio.Media.EXTERNAL_CONTENT_URI, MediaStore.Audio.Media.DATA + "=?", new String[] { path } );
	}

	public static void PickMedia( Context context, final NativeGalleryMediaReceiver mediaReceiver, int mediaType, boolean selectMultiple, String savePath, String mime, String title )
	{
		if( CheckPermission( context, true ) != 1 )
		{
			if( !selectMultiple )
				mediaReceiver.OnMediaReceived( "" );
			else
				mediaReceiver.OnMultipleMediaReceived( "" );

			return;
		}

		Bundle bundle = new Bundle();
		bundle.putInt( NativeGalleryMediaPickerFragment.MEDIA_TYPE_ID, mediaType );
		bundle.putBoolean( NativeGalleryMediaPickerFragment.SELECT_MULTIPLE_ID, selectMultiple );
		bundle.putString( NativeGalleryMediaPickerFragment.SAVE_PATH_ID, savePath );
		bundle.putString( NativeGalleryMediaPickerFragment.MIME_ID, mime );
		bundle.putString( NativeGalleryMediaPickerFragment.TITLE_ID, title );

		final Fragment request = new NativeGalleryMediaPickerFragment( mediaReceiver );
		request.setArguments( bundle );

		( (Activity) context ).getFragmentManager().beginTransaction().add( 0, request ).commit();
	}

	@TargetApi( Build.VERSION_CODES.M )
	public static int CheckPermission( Context context, final boolean readPermissionOnly )
	{
		if( Build.VERSION.SDK_INT < Build.VERSION_CODES.M )
			return 1;

		if( context.checkSelfPermission( Manifest.permission.READ_EXTERNAL_STORAGE ) == PackageManager.PERMISSION_GRANTED )
		{
			if( readPermissionOnly || context.checkSelfPermission( Manifest.permission.WRITE_EXTERNAL_STORAGE ) == PackageManager.PERMISSION_GRANTED )
				return 1;
		}

		return 0;
	}

	// Credit: https://github.com/Over17/UnityAndroidPermissions/blob/0dca33e40628f1f279decb67d901fd444b409cd7/src/UnityAndroidPermissions/src/main/java/com/unity3d/plugin/UnityAndroidPermissions.java
	public static void RequestPermission( Context context, final NativeGalleryPermissionReceiver permissionReceiver, final boolean readPermissionOnly, final int lastCheckResult )
	{
		if( CheckPermission( context, readPermissionOnly ) == 1 )
		{
			permissionReceiver.OnPermissionResult( 1 );
			return;
		}

		if( lastCheckResult == 0 ) // If user clicked "Don't ask again" before, don't bother asking them again
		{
			permissionReceiver.OnPermissionResult( 0 );
			return;
		}

		Bundle bundle = new Bundle();
		bundle.putBoolean( NativeGalleryPermissionFragment.READ_PERMISSION_ONLY, readPermissionOnly );

		final Fragment request = new NativeGalleryPermissionFragment( permissionReceiver );
		request.setArguments( bundle );

		( (Activity) context ).getFragmentManager().beginTransaction().add( 0, request ).commit();
	}

	// Credit: https://stackoverflow.com/a/35456817/2373034
	public static void OpenSettings( Context context )
	{
		Uri uri = Uri.fromParts( "package", context.getPackageName(), null );

		Intent intent = new Intent();
		intent.setAction( Settings.ACTION_APPLICATION_DETAILS_SETTINGS );
		intent.setData( uri );

		context.startActivity( intent );
	}

	public static boolean CanSelectMultipleMedia()
	{
		return Build.VERSION.SDK_INT >= Build.VERSION_CODES.JELLY_BEAN_MR2;
	}

	public static boolean CanSelectMultipleMediaTypes()
	{
		return Build.VERSION.SDK_INT >= Build.VERSION_CODES.KITKAT;
	}

	public static String GetMimeTypeFromExtension( String extension )
	{
		if( extension == null || extension.length() == 0 )
			return "";

		String mime = MimeTypeMap.getSingleton().getMimeTypeFromExtension( extension.toLowerCase( Locale.ENGLISH ) );
		return mime != null ? mime : "";
	}

	public static String LoadImageAtPath( Context context, String path, final String temporaryFilePath, final int maxSize )
	{
		return NativeGalleryUtils.LoadImageAtPath( context, path, temporaryFilePath, maxSize );
	}

	public static String GetImageProperties( Context context, final String path )
	{
		return NativeGalleryUtils.GetImageProperties( context, path );
	}

	@TargetApi( Build.VERSION_CODES.JELLY_BEAN_MR1 )
	public static String GetVideoProperties( Context context, final String path )
	{
		return NativeGalleryUtils.GetVideoProperties( context, path );
	}

	@TargetApi( Build.VERSION_CODES.Q )
	public static String GetVideoThumbnail( Context context, final String path, final String savePath, final boolean saveAsJpeg, int maxSize, double captureTime )
	{
		return NativeGalleryUtils.GetVideoThumbnail( context, path, savePath, saveAsJpeg, maxSize, captureTime );
	}
}