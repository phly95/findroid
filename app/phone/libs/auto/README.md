# Legacy Android Auto SDK (optional "auto" flavor)

The `auto` product flavor of `:app:phone` is a Fermata-style Android Auto *projection* build. It is
only registered when `aauto.aar` is present in this directory, so the regular build and CI are
unaffected without it.

`aauto.aar` is Google's legacy Android Auto projection SDK (`com.google.android.apps.auto.sdk`).
It is proprietary and is deliberately **not** committed. Fermata ships a copy:

    https://github.com/AndreyPavlenko/Fermata/raw/master/fermata/lib/auto/aauto.aar

Copy it here, then build with `./gradlew :app:phone:assembleAutoDebug`.
