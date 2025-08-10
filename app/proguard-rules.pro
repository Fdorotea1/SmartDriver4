# ---- Regras base do projeto ----
# Mantém as classes utilitárias e modelos em utils (métodos e campos)
-keep class com.example.smartdriver.utils.** { *; }

# Mantém as views de overlay (evita remoções/renomes que partem reflexão e layouts)
-keep class com.example.smartdriver.overlay.OverlayView { *; }
-keep class com.example.smartdriver.overlay.TrackingOverlayView { *; }
-keep class com.example.smartdriver.overlay.MenuView { *; }

# Atributos úteis para genéricos/anotações
-keepattributes Signature, InnerClasses, EnclosingMethod, RuntimeVisibleAnnotations, RuntimeInvisibleAnnotations, *Annotation*

# Gson (evitar warnings e manter nomes quando necessário)
-dontwarn com.google.gson.**
-keep class com.google.gson.stream.** { *; }

# AndroidX Lifecycle (evitar ruído)
-dontwarn androidx.lifecycle.**

# Trip history e modelos (se forem serializados por reflexão)
-keep class com.example.smartdriver.utils.TripHistoryEntry { *; }
-keep class com.example.smartdriver.utils.OfferData { *; }
-keep class com.example.smartdriver.utils.EvaluationResult { *; }
-keep class com.example.smartdriver.utils.BorderRating { *; }
-keep class com.example.smartdriver.utils.IndividualRating { *; }

# ---- Fim ----
