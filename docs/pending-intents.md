# Mylite PendingIntent capabilities

The server scopes private Binder capabilities to the creator's real signer, virtual user and committed APK generation. Broadcast, Service and foreground Service have distinct identities. Explicit package-local Service sends use the existing component router and actual Android Service lifecycle; foreground sends retain their real foreground requirement. Cancellation does not stop a Service already running. Service OnFinished acknowledges route acceptance or failure, not asynchronous business completion.

Installer status still requires a live mutable broadcast capability. Service and foreground Service senders are rejected before the session is sealed, preserving its ability to write or abandon.

Broadcast capability runtime tests previously passed. The new Service test covers actual OS Service/notification state, cross-process transfer, creator/type queries, updates, cancellation, mutability, one-shot delivery and installer rejection. Local build/lint/artifact checks pass; Service runtime acceptance is pending.

These capabilities are not Android PendingIntentRecord objects. AlarmManager, system network callbacks, external OS delivery and recovery after server death need a real OS bridge. Activity senders, implicit Service targets, cross-package grants and arbitrary options remain unsupported. These results do not establish microG registration, MCS push or Play download support.
