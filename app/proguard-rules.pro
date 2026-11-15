# Activities and the accessibility service are kept automatically via the manifest.
# The status-bar expansion uses framework reflection (android.app.StatusBarManager),
# which R8 never touches, so no rules are needed.

# Strip Kotlin null-check intrinsics overhead in release.
-assumenosideeffects class kotlin.jvm.internal.Intrinsics {
    static void checkParameterIsNotNull(...);
    static void checkNotNullParameter(...);
    static void checkExpressionValueIsNotNull(...);
    static void checkNotNullExpressionValue(...);
}
