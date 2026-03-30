The google photos API changes made third party photo frame apps lose the option to select full google photos albums conveniently. Those that still can are expected to lose the feature in the future. The android screensaver using google photos can still select full albums though, but it scrolls fast through the photos and is not fully random. Instead it displays multiple photos from one album before going to the next.

To regain the option to set photo interval and reintroduce more random selecitons, this is my workaround. The app makes a screenshot of the screensaver and overlays it to freeze the screen for a set interval, when it switches it removes the overlay and repeats. One tap on the screen manually forces a refresh, double tap to exit.

It targets my Galaxy Tab A with Android 7.1.1., it does not seem to work on my newer android phone. Use as follows:
- Set screensaver to activate on 30 seconds or shorter
- Open the app, choose interval, start the service
- It will freeze the screen (but thats the app UI)
- Let the screensaver start (in the background)
- After 1 minute it will refresh so a photo is show
- From then on, it refreshes on the set interval
