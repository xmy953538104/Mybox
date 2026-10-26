# Mylite PendingIntent capabilities

The current [Service alarm candidate](os-service-alarms.md) adds durable immutable manifest Service senders at the OS boundary. It remains pending cloud validation; virtual sends and OS scheduling are separate acceptance claims.

The server scopes private Binder capabilities to the creator's real signer, virtual user and committed APK generation. Broadcast, Service and foreground Service have distinct identities. Explicit package-local Service sends use the existing component router and actual Android Service lifecycle; foreground sends retain their real foreground requirement. Cancellation does not stop a Service already running. Service OnFinished acknowledges route acceptance or failure, not asynchronous business completion.

Installer status still requires a live mutable broadcast capability. Service and foreground Service senders are rejected before the session is sealed, preserving its ability to write or abandon.

The Service test covers actual OS Service/notification state, cross-process transfer, creator/type queries, updates, cancellation, mutability, one-shot delivery and installer rejection. It passed all three Android14/x86_64 rounds in [CI40070852561](https://github.com/xmy953538104/Mybox/actions/runs/40070852561), together with existing broadcast regressions and build/lint/artifact checks.

These capabilities are not Android PendingIntentRecord objects. AlarmManager, system network callbacks, external OS delivery and recovery after server death need a real OS bridge. Activity senders, implicit Service targets, cross-package grants and arbitrary options remain unsupported. These results do not establish microG registration, MCS push or Play download support.
