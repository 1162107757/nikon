package com.nikonpreset.usbprobe.album;
/** The gallery suite exercises no camera operations. */
public final class AlbumController { public interface Callback<T> { void onSuccess(T result); void onError(String message); } }
