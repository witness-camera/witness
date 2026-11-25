# kotlinx.serialization reaches generated serializers reflectively through each
# class's Companion. The library ships its own consumer rules, but the app has
# @Serializable types outside the model package too - the on-disk upload queue
# envelope and the event-log entry - and losing either would only show up at
# runtime, mid-recording. Keep them all explicitly.
-keepattributes *Annotation*, InnerClasses
-dontnote kotlinx.serialization.**

-keepclassmembers class com.witnessrecord.app.** {
    *** Companion;
}
-keepclasseswithmembers class com.witnessrecord.app.** {
    kotlinx.serialization.KSerializer serializer(...);
}
-keep,includedescriptorclasses class com.witnessrecord.app.**$$serializer { *; }

# Play Services' AuthorizationClient (com.google.android.gms.auth.api.identity,
# used for Google Drive sign-in) hands its result back across a process
# boundary as a Parcelable, reconstructed on receipt by *this app's own* copy
# of AuthorizationResult's CREATOR - not the sender's. If R8 strips or renames
# anything that reconstruction needs, the failure happens inside Play
# Services' own code, which is written to swallow it rather than throw: a
# real, granted authorization comes back indistinguishable from one that was
# never given - no crash, nothing in the log, the account picker completing
# normally right before it. Play Services ships its own consumer rules, but
# they have not proven to fully cover this specific, newer API.
-keep class com.google.android.gms.auth.api.identity.** { *; }
-keep interface com.google.android.gms.auth.api.identity.** { *; }
-keep class com.google.android.gms.common.api.Scope { *; }
