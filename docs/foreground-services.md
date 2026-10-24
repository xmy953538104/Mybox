# Mylite foreground Services: first checkpoint

Each started guest Service leases its own real Android Service stub and token; four slots are available per guest process. `startForegroundService` retains the actual foreground requirement. Android's onStartCommand drives the guest callback; promotion/stop use the corresponding real OS token. Start IDs, independent stopSelf, pending starts, REMOVE and DETACH are tracked separately for each Service. A fifth simultaneous started Service fails explicitly.

Notifications/channels/groups are mapped into a server-controlled namespace based on virtual user, package and real certificate. Original tag/ID pairs, including negative IDs, map to persistent distinct physical IDs. Supported platform templates are rebuilt with host-usable icons and resources. Foreground success depends on Android accepting the real promotion; an internal boolean is not acceptance. Requested foreground types remain constrained by actual host capability and verified package/service identity.

The fixture checks two simultaneous independent foreground ServiceRecords and real posted notifications/channels, icon/progress conversion, repeated starts, stale start IDs, independent stopping, REMOVE/DETACH and slot exhaustion. Runtime tests must observe OS state, not merely a guest report.

Current limits: sticky/redelivery recovery after OS process death; authenticated task-removal callback restoration; custom RemoteViews, interaction PendingIntents, bubbles and unknown styles; additional foreground types/permissions and guest-specific sound URI forwarding. Unsupported guest requests fail explicitly; unverified task-removal payloads are not forwarded across guest boundaries. This is not complete microG registration/MCS or Play download acceptance.

See [STATUS](STATUS.md) for the exact accepted runs and outstanding failures.
