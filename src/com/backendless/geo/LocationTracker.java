/*
 * ********************************************************************************************************************
 *  <p/>
 *  BACKENDLESS.COM CONFIDENTIAL
 *  <p/>
 *  ********************************************************************************************************************
 *  <p/>
 *  Copyright 2012 BACKENDLESS.COM. All Rights Reserved.
 *  <p/>
 *  NOTICE: All information contained herein is, and remains the property of Backendless.com and its suppliers,
 *  if any. The intellectual and technical concepts contained herein are proprietary to Backendless.com and its
 *  suppliers and may be covered by U.S. and Foreign Patents, patents in process, and are protected by trade secret
 *  or copyright law. Dissemination of this information or reproduction of this material is strictly forbidden
 *  unless prior written permission is obtained from Backendless.com.
 *  <p/>
 *  ********************************************************************************************************************
 */

package com.backendless.geo;

import android.app.Service;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.location.Criteria;
import android.location.Location;
import android.location.LocationListener;
import android.location.LocationManager;
import android.os.Bundle;
import android.os.IBinder;
import android.preference.PreferenceManager;
import android.util.Base64;
import android.util.Log;
import com.backendless.Backendless;
import com.backendless.exceptions.BackendlessException;
import com.backendless.exceptions.ExceptionMessage;
import weborb.util.io.ISerializer;
import weborb.util.io.Serializer;

import java.io.*;
import java.util.Collections;
import java.util.HashMap;
import java.util.Map;

/**
 * Created by julia
 */
public class LocationTracker extends Service implements LocationListener
{
  private static final String APPLICATION_ID = "applicationId";
  private static final String SECRET_KEY = "secretKey";
  private static final String VERSION = "version";
  private static final String URL = "url";
  private static final String LOCATION = "location";
  private static final String LOCATION_LISTENERS = "locationListeners";

  private static final int MIN_TIME = 60 * 1000; // 1 minute
  private static final int MIN_DISTANCE = 10; // meters
  public static float ACCEPTABLE_DISTANCE = 30; // meters

  private static LocationTracker instance;

  private LocationManager locationManager;
  private Map<String, IBackendlessLocationListener> locationListeners;
  private String provider;

  public LocationTracker()
  {
  }

  @Override
  public void onCreate()
  {
    super.onCreate();

    initApplication();
    init();
    initLocationListeners();

    if( locationListeners != null && !locationListeners.isEmpty() )
    {
      listenBestProvider();
      changeLocation();
    }

    saveApplicationInfo();
  }

  @Override
  public int onStartCommand( Intent intent, int flags, int startId )
  {
    instance = this;
    return START_STICKY;
  }

  @Override
  public IBinder onBind( Intent intent )
  {
    return null;
  }

  public static LocationTracker getInstance()
  {
    return instance;
  }

  @Override
  public void onLocationChanged( Location location )
  {
    if( location != null )
    {
      locationChanged( location );
      saveLocation( location );
    }
  }

  @Override
  public void onStatusChanged( String s, int i, Bundle bundle )
  {
    listenBestProvider();
  }

  @Override
  public void onProviderEnabled( String s )
  {
    listenBestProvider();
  }

  @Override
  public void onProviderDisabled( String s )
  {
    if( s.equals( provider ) )
    {
      listenBestProvider();
    }
  }

  public void addListener( String name, IBackendlessLocationListener locationListener )
  {

    if( locationListeners.isEmpty() )
    {
      listenBestProvider();
    }
    this.locationListeners.put( name, locationListener );

    firstListen( locationListener );

    saveLocationListeners();
  }

  public IBackendlessLocationListener getListener( String name )
  {
    return locationListeners.get( name );
  }

  public Map<String, IBackendlessLocationListener> getLocationListeners()
  {
    return locationListeners;
  }

  public void removeListener( String name )
  {
    locationListeners.remove( name );
    if( locationListeners.size() == 0 )
    {
      locationManager.removeUpdates( this );
    }

    saveLocationListeners();
  }

  private void init()
  {
    locationManager = (LocationManager) getSystemService( Context.LOCATION_SERVICE );
    locationListeners = Collections.synchronizedMap( new HashMap<String, IBackendlessLocationListener>() );
  }

  private void listenBestProvider()
  {
    String bestProvider = locationManager.getBestProvider( new Criteria(), true );

    if( bestProvider == null )
      throw new BackendlessException( ExceptionMessage.NOT_FOUND_PROVIDER );

    if( !bestProvider.equals( provider ) )
    {
      listenProvider( bestProvider );
    }
  }

  private void listenProvider( String provider )
  {
    this.provider = provider;
    locationManager.removeUpdates( this );
    locationManager.requestLocationUpdates( this.provider, MIN_TIME, MIN_DISTANCE, this );
  }

  private void firstListen( IBackendlessLocationListener locationListener )
  {
    Location location = null;
    try
    {
      location = locationManager.getLastKnownLocation( provider );
    }
    catch( Exception ex )
    {
    }
    if( location != null )
      locationListener.onLocationChanged( location );
  }

  private void locationChanged( Location location )
  {
    for( String name : locationListeners.keySet() )
    {
      locationListeners.get( name ).onLocationChanged( location );
    }
    saveLocationListeners();
  }

  private void initApplication()
  {
    SharedPreferences sharedPref = PreferenceManager.getDefaultSharedPreferences( getApplicationContext() );
    String url = sharedPref.getString( URL, null );
    if( url != null )
    {
      Backendless.setUrl( url );
      Backendless.initApp( this, sharedPref.getString( APPLICATION_ID, null ), sharedPref.getString( SECRET_KEY, null ), sharedPref.getString( VERSION, null ) );
    }
  }

  private void saveApplicationInfo()
  {
    SharedPreferences sharedPref = PreferenceManager.getDefaultSharedPreferences( getApplicationContext() );
    SharedPreferences.Editor editor = sharedPref.edit();
    editor.putString( APPLICATION_ID, Backendless.getApplicationId() );
    editor.putString( VERSION, Backendless.getVersion() );
    editor.putString( SECRET_KEY, Backendless.getSecretKey() );
    editor.putString( URL, Backendless.getUrl() );
    editor.apply();
  }

  private void initLocationListeners()
  {
    File file = new File( getApplicationContext().getDir( "data", MODE_PRIVATE ), LOCATION_LISTENERS );
    ObjectInputStream inputStream = null;
    try
    {
      inputStream = new ObjectInputStream( new FileInputStream( file ) );

      Map<String, IBackendlessLocationListener> serializedListeners = null;
      try
      {
        serializedListeners = (Map<String, IBackendlessLocationListener>) inputStream.readObject();
        if( serializedListeners != null )
        {
          locationListeners = serializedListeners;
        }
      }
      catch( ClassNotFoundException e )
      {
        Log.e( "Cannot get location listeners", e.getMessage() );
      }
    }
    catch( IOException e )
    {
      Log.e( "Cannot get location listeners", e.getMessage() );
    }
    finally
    {
      if( inputStream != null )
      {
        try
        {
          inputStream.close();
        }
        catch( IOException e )
        {

        }
      }
    }
  }

  private void saveLocationListeners()
  {
    File file = new File( getApplicationContext().getDir( "data", MODE_PRIVATE ), LOCATION_LISTENERS );
    ObjectOutputStream outputStream = null;
    try
    {
      outputStream = new ObjectOutputStream( new FileOutputStream( file ) );
      outputStream.writeObject( locationListeners );
    }
    catch( IOException e )
    {
      Log.e( "Cannot save location listeners", e.getMessage() );
    }
    finally
    {
      if( outputStream != null )
      {
        try
        {
          outputStream.flush();
          outputStream.close();
        }
        catch( IOException e )
        {

        }
      }
    }
  }

  private void changeLocation()
  {
    Location oldLocation = getSavedLocation();

    if( provider == null )
    {
      listenBestProvider();
    }

    Location location = locationManager.getLastKnownLocation( provider );
    if( oldLocation != null )
    {
      for( String name : locationListeners.keySet() )
      {
        locationListeners.get( name ).onLocationChanged( oldLocation, location );
      }
    }
  }

  private Location getSavedLocation()
  {
    SharedPreferences sharedPref = PreferenceManager.getDefaultSharedPreferences( getApplicationContext() );
    String locationStr = sharedPref.getString( LOCATION, null );

    if( locationStr == null )
    {
      return null;
    }

    try
    {
      return (Location) Serializer.fromBytes( Base64.decode( locationStr, Base64.DEFAULT ), ISerializer.AMF3, false );
    }
    catch( IOException e )
    {
      Log.e( "Cannot get location", e.getMessage() );
      return null;
    }
  }

  private void saveLocation( Location location )
  {
    SharedPreferences sharedPref = PreferenceManager.getDefaultSharedPreferences( getApplicationContext() );
    SharedPreferences.Editor editor = sharedPref.edit();
    try
    {
      editor.putString( LOCATION, Base64.encodeToString( Serializer.toBytes( location, ISerializer.AMF3 ), Base64.DEFAULT ) );
    }
    catch( Exception e )
    {
      Log.e( "Cannot save location", e.getMessage() );
    }
    editor.apply();
  }
}
