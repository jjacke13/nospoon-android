{ pkgs ? import (fetchTarball "https://github.com/NixOS/nixpkgs/archive/nixpkgs-unstable.tar.gz") {
    config.android_sdk.accept_license = true;
    config.allowUnfree = true;
  }
}:

let
  androidComposition = pkgs.androidenv.composeAndroidPackages {
    platformVersions = [ "35" ];
    buildToolsVersions = [ "35.0.0" ];
    includeNDK = false;
    includeEmulator = false;
    includeSystemImages = false;
  };

  androidSdk = androidComposition.androidsdk;
in
pkgs.mkShell {
  name = "nospoon-android";

  buildInputs = [
    androidSdk
    pkgs.jdk17
    pkgs.nodejs
    pkgs.gh
    pkgs.unzip
  ];

  ANDROID_HOME = "${androidSdk}/libexec/android-sdk";
  ANDROID_SDK_ROOT = "${androidSdk}/libexec/android-sdk";
  JAVA_HOME = "${pkgs.jdk17}";

  # Use Nix-provided AAPT2 instead of Gradle's dynamically-linked download
  GRADLE_OPTS = "-Dorg.gradle.project.android.aapt2FromMavenOverride=${androidSdk}/libexec/android-sdk/build-tools/35.0.0/aapt2";

  shellHook = ''
    echo "nospoon Android dev shell"
    echo ""
    echo "  ANDROID_HOME=$ANDROID_HOME"
    echo "  JAVA_HOME=$JAVA_HOME"
    echo ""
    echo "Build: ./build.sh"
  '';
}
