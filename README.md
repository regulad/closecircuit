Current Status: Proof of concept

## Close Circuit (Weekend Project #3)

Close as in close-by, not close as in shut down. 

This project lets you create a PAN surveillance system using WiFi and IP Webcams. The monitor functions as an AP using the Wi-Fi Direct APIs, and can have multiple Wi-Fi cameras connected to it. 

The monitor runs the CC app (contained in the `app` directory), and the cameras may be either traditional IP cameras or Android devices running the https://play.google.com/store/apps/details?id=com.pas.webcam&hl=en_US app.

## Using

When the app first launches, it will display a QR code that you can scan with your camera device to connect to the monitor. It will also show the SSID and password that you can use to connect to the monitor manually.

When the monitor detects that any IP webcams with an HTTP server at port 8080 and with an MJPEG stream at `/video` are connected to it, it will display the video feed from the first camera it finds. If the camera disconnects, the monitor will remove it from view but will continue to attempt a connection in the background.

## Requirements

* WiFi support
* Android 5.0 or later (use Android 10.0 or later if you would like the network SSID/password to stay the same between reboots, versions >=5.0 and <10.0 will generate a new SSID/password each time the monitor is started)
* Some cameras (android cameras, etc.)
 
## Applications

* Camps in the wilderness
* Backup/front camera for your car ([land yacht!](https://www.tiktok.com/@insanegnyc/video/7258434298699861294?q=camera%20%23cadillacescalade&t=1729559185863))
