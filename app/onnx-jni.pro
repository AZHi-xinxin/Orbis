# ONNX calls Java constructors, fields and helpers by their original JNI names.
# Native callers are invisible to R8; keepnames/native-method-only rules are insufficient.
# In particular, TensorInfo(long[], String[], int) is needed by convertToTensorInfo.
# Apply to release and the rescue build inheriting release. Do not allow optimization
# or shrinking here: either can change JNI descriptors even when class names survive.
# https://onnxruntime.ai/docs/build/android.html#note-proguard-rules-for-r8-minimization-android-app-builds-to-work
-keep class ai.onnxruntime.** { *; }
