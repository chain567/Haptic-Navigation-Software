# Local configuration

Copy `local.properties.example` to `local.properties` and enter your own Maps key and left/right Bluetooth device addresses. The local file is ignored by Git.

Keep credentials, signing keys, and key-bearing APKs out of the repository. The Maps key and device address are compiled into the app, so local configuration keeps them out of source control but does not hide them inside an APK.

## Google Maps setup

The prototype uses Maps SDK for Android and the Directions web service. Review the enabled APIs, billing, and key restrictions for your own Google Cloud project before running it.

The current app uses one configured key for both integrations. For wider deployment, use an appropriate mobile SDK or a protected backend for the web-service calls, with separate credential restrictions.

[Google Maps Platform configuration guidance](https://developers.google.com/maps/api-security-best-practices)
