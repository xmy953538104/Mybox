# Mylite cross-package account UI: B2a

The account server freezes the consumer and authenticator's real signers and committed APK generations before asynchronous authentication. An authenticated response may authorize one exact standard Activity owned by that authenticator. A bounded, expiring Binder ticket carries that authority; unrelated packages, compatible signature views and the shared physical UID cannot grant it.

The server retains the original Intent, extras and response Binder. Launch restores that snapshot, rejects structural changes and consumes authority at the real Activity token claim. Both installed principals are checked again at reservation, dispatch and claim. A definite failure may release an unclaimed ticket only after its old record is retired; uncertain dispatch, expiry, changed installation or a completed claim cannot be retried. With expectActivity=false, the returned Intent has its own bounded lifetime after the original Future closes.

Two independent fixture APKs exercise automatic UI/result delivery, a delayed manual launch, the original response/payload, same-signer wrong-package rejection, replay, actual AMS failure and signed updates. Internal consumption-boundary tests separately check updates after reservation. The suite runs in its own process-slot group three times, retaining data between rounds. Five JVM state tests and local APK/lint checks pass; actual Android B2a results are pending.

B2a supports explicit standard authenticator UI only. Cross-package singleTask/task reuse, arbitrary exported-app navigation, account chooser/system UI, URI grants and server-death ticket recovery remain unsupported. This is not acceptance of real Google login, Play or push.
