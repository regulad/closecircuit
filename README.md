# ![Close Circuit Icon](app/src/main/res/mipmap-mdpi/ic_launcher.webp) Close Circuit

## Weekend Project #3

<p style="float: left;">
    <img src="./pictures/IMG_2854.jpg" width="500" style="float: left; margin-right: 10px;">
    <img src="./pictures/IMG_2853.jpg" width="500" style="float: left; margin-right: 10px;">
</p>

> Close Circuit is running on the phone mounted on the dashboard.
> The phone in the back is running the IP Webcam app, linked below.

> [The license plate mount is of my own design, you can download the .STEP and .STL for remixing or printing here.](https://www.printables.com/model/1050371-zte-speed-license-plate-mount)

Close as in close-by, not close as in shut down. The name is a play on CCTV.

This project lets you create a PAN surveillance system using WiFi IP Webcams. The monitor functions as an AP using
the Wi-Fi Direct APIs, and can have multiple Wi-Fi cameras connected to it.

The terminal monitor runs the CC app (which you are currently looking at), and the cameras are other
Android devices running the [Android IP Webcam](https://play.google.com/store/apps/details?id=com.pas.webcam&hl=en_US)
app.

## Feats

- [WS-Discovery](https://docs.oasis-open.org/ws-dd/discovery/1.1/wsdd-discovery-1.1-spec.html) (a.k.a. WSDD, SMB
  Discovery) client for finding cameras written in Android with 100% Kotlin (and some Java XML
  libraries) [(watch a demo here)](https://youtu.be/R4aS2WIKhBE?si=MlXTYtz3vMyXOSmo)

## Future Goals

- Generic ONVIF support
- Audio streaming

## Using

When the app first launches, it will display a QR code that you can scan with your camera device to connect to the
monitor. It will also show the SSID and password that you can use to connect to the monitor manually.

The monitor will then scan the network using WSD to find phones running Android IP Webcam. All Cameras will be displayed
in a grid view.

The network is completely airgapped, making it perfect for tiny security installations.

Although I haven't tested battery efficiency, I anticipate it being quite good for the cameras, but so-so for the
terminal viewer. My suggestion is to keep the terminal attached to a charger, but the camera's probably won't need one,
unless the target phone has a very inefficient WiFi Radio.

## Applications

* Camps in the wilderness
* Backup/front camera for your
  car ([land yacht!](https://www.tiktok.com/@insanegnyc/video/7258434298699861294?q=camera%20%23cadillacescalade&t=1729559185863))
* Baby monitors (no sound implemented)

## Requirements

* WiFi support
* Android 6.0 or later
* Some devices to use as
  cameras ([Android IP Webcam](https://play.google.com/store/apps/details?id=com.pas.webcam&hl=en_US) supports KitKat
  and later)

## Screenshots

Pairing screen

![Screenshot_20241224-195107.png](pictures%2FScreenshot_20241224-195107.png)
