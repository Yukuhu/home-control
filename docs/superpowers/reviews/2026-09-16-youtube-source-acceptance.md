# YouTube source — manual acceptance (sub-project E)

Automated coverage: `YouTubeEndToEndTest` and the unit tests run against `FakeGoogleServer`, `FakeCastReceiver` and
`FakeRemoteServer`. Nothing below has been run against Google, YouTube or real devices. An agent must never mark an
item passed.

Environment to record: Home Control version, Google Cloud project (publishing status), Shield OS and YouTube app
version, Chromecast model and firmware, LG webOS version, Samsung model year.

## Google authorization

| # | Check | Result |
|---|---|---|
| 1 | Follow the setup page's steps in a fresh Google Cloud project; the OAuth client of type “TVs and Limited Input devices” is accepted | Pending — requires real hardware |
| 2 | Connect: the page shows a code; entering it at google.com/device on a phone and allowing access turns the page to “Connected as <channel>” within a few seconds | Pending — requires real hardware |
| 3 | Deny on the phone: the page says access was denied; let a code expire (30 min): the page says it expired | Pending — requires real hardware |
| 4 | A client of type “Web application” is refused with the “TVs and Limited Input devices” message | Pending — requires real hardware |
| 5 | Restart the container: still connected, rails load without a new code | Pending — requires real hardware |
| 6 | Consent screen left in “Testing”: after 7 days the rails show the reconnect message naming “Testing” (record the date) | Pending — requires real hardware |
| 7 | Remove Home Control's access at myaccount.google.com → Security → Third-party access: rails show the reconnect message | Pending — requires real hardware |
| 8 | YouTube Data API v3 not enabled: the rail says it is not enabled in the Cloud project | Pending — requires real hardware |
| 9 | Disconnect: the grant disappears from the Google account's third-party access list; secrets.json no longer contains YouTube entries | Pending — requires real hardware |

## Rails, quota and search

| # | Check | Result |
|---|---|---|
| 10 | “New from your subscriptions” lists the same recent uploads as youtube.com/feed/subscriptions (ignoring Shorts and live) | Pending — requires real hardware |
| 11 | After a day of normal use the quota shown in Setup is within 5 % of the Cloud console's “Queries per day” for the YouTube Data API | Pending — requires real hardware |
| 12 | Watch Later switched on: record whether YouTube returns items or the rail shows the 2016 explanation | Pending — requires real hardware |
| 13 | Load playlists, choose two: both appear as rails in playlist order; reorder and hide them in Sources | Pending — requires real hardware |
| 14 | Typing in search does not use quota; “Search YouTube” shows results and the remaining-searches note decreases by one; repeating the same search does not | Pending — requires real hardware |
| 15 | After the daily search cap the button's result explains when searches return | Pending — requires real hardware |
| 16 | Thumbnails load on a phone and the image requests go to Home Control, not to i.ytimg.com | Pending — requires real hardware |

## Playback

| # | Check | Result |
|---|---|---|
| 17 | Shield (Android TV): playing a subscription tile starts that video in the YouTube app (“Open in the YouTube app”) | Pending — requires real hardware |
| 18 | Shield with the YouTube app not in the foreground / after a reboot: the video still starts | Pending — requires real hardware |
| 19 | LG webOS: the same tile starts the video (F's content target) — record the webOS version | Pending — requires real hardware |
| 20 | Samsung Tizen: the same tile starts the video through DIAL | Pending — requires real hardware |
| 21 | Chromecast with YouTube Cast off: the play sheet says the device cannot open app links | Pending — requires real hardware |
| 22 | Chromecast with YouTube Cast on: “Cast with the YouTube receiver (best effort)” starts the video; record how long it takes and whether a phone's YouTube app shows “Home Control” as connected | Pending — requires real hardware |
| 23 | Merged Shield (Android TV + Cast) with YouTube Cast on: app link first; if it fails, the toast offers the YouTube Cast route and it works | Pending — requires real hardware |
| 24 | A Cast device with the YouTube receiver already playing something: the new video replaces it | Pending — requires real hardware |
| 25 | A pasted youtu.be link on the open-link form plays on the Shield and (with the switch on) on the Chromecast | Pending — requires real hardware |
| 26 | Record any Lounge failure message seen and the date; confirm that switching YouTube Cast off restores the plain behaviour | Pending — requires real hardware |

## Findings

(none recorded yet)
