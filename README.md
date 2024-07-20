
# `adblib` - Advanced ADB Library

![AdbLib Logo](https://example.com/logo.png) <!-- Replace with actual logo URL -->

`adblib` is a powerful Java/Kotlin library that enables programmatic access to ADB services via a socket channel. This library provides the functionalities that can be invoked from the `adb` shell command, making it highly useful for various `host` environments, from simple command-line applications to complex desktop applications like Android Studio and IntelliJ IDEA.

## Overview

`adblib` is part of the platform tools source code used to build Android Studio or the IntelliJ IDEA IDE. You can find it in the [AOSP repository](https://android.googlesource.com/platform/tools/base/+/refs/heads/mirror-goog-studio-main/adblib/). The goal of this repository is to enable building `adblib` without cloning the entire platform repository, using non-local dependencies usually shipped with the platform tools.

Read the [README.origin.md](https://github.com/DevOculus-Meta-Quest/AdbLib-Google-PlatformTools/blob/main/README.origin.md) for more information about `adblib`.

## Features

- **Service Invocation:** Programmatically access ADB services via a socket channel.
- **Customizable Environments:** Supports various environments, including command line, servers, and IDEs.
- **Inversion of Control:** No global mutable state, allowing communication with multiple ADB hosts in a single JVM instance.
- **Asynchronous APIs:** Uses coroutines and asynchronous APIs to avoid blocking threads.
- **Performance Optimization:** Avoids excessive memory allocations to reduce GC heap usage.
- **Logging:** Custom `AdbLogger` abstraction for wide compatibility with logging facilities.
- **Metrics:** Capability to collect performance and reliability metrics (TBD).

## Installation

To build `adblib`, you can use the following command:

```shell
bazel build //:adblib
```

## Usage

### Example: Tracking Devices

This example demonstrates how to use `adblib` to track all new devices connected through ADB, similar to `adb track-devices`:

```kotlin
import com.android.adblib.AdbSession
import com.android.adblib.AdbSessionHost
import com.android.adblib.trackDevices
import kotlinx.coroutines.runBlocking

fun main(args: Array<String>) {
    val host = AdbSessionHost()
    val session = AdbSession.create(host)
    val devicesFlow = session.trackDevices()
    runBlocking {
        devicesFlow.collect { deviceList ->
            println("### Change in state ###")
            deviceList.devices.forEach { device ->
                println("${device.model}: ${device.serialNumber}: ${device.deviceState}")
            }
        }
    }
}
```

Output while connecting and disconnecting a Pixel 5 phone:

```shell
### Change in state ###
### Change in state ###
Pixel_8: XXXXXXXXXXXXXX: ONLINE
### Change in state ###
Pixel_8: XXXXXXXXXXXXXX: OFFLINE
### Change in state ###
```

## Integrating with Android Studio

To use `adblib` in Android Studio:

1. **Add Dependency:**
   Add the `adblib` dependency to your `build.gradle` file:

   ```gradle
   dependencies {
       implementation 'com.android.adblib:adblib:<latest-version>'
   }
   ```

2. **Configure Logging:**
   Set up logging in Android Studio by opening the "Debug Log Settings" dialog and adding the following entries:

   ```
   #com.android.adblib.tools.debugging.impl.JdwpProcessPropertiesCollector
   #com.android.adblib.tools.debugging.impl.JdwpSessionProxy
   #com.android.adblib.tools.debugging.impl.JdwpProcessImpl
   #com.android.adblib.tools.debugging.DdmsPacketsHandler
   #com.android.adblib.impl.AdbBufferedInputChannel
   #com.android.adblib.tools.debugging.impl.JdwpSessionProxy:all
   #com.android.adblib.tools.debugging.impl.SharedJdwpSessionImpl:all
   #com.android.adblib.tools.debugging.impl.SharedJdwpSessionImpl$JdwpPacketReceiverImpl$ReceiverFlowImpl:all
   #com.android.adblib.tools.debugging.impl.SharedJdwpSessionImpl$PacketSender:all
   #com.android.adblib.tools.debugging.impl.JdwpSessionImpl:all
   #com.android.adblib.tools.debugging.SharedJdwpSession:all
   #com.android.adblib.impl.AdbWriteBackOutputChannel:all
   #com.android.adblib.impl.AdbWriteBackOutputChannel$WriteBackWorker:all
   #com.android.adblib.impl.AdbReadAheadInputChannel:all
   #com.android.adblib.tools.debugging.impl.JdwpProcessProfilerImpl:all
   #com.android.adblib.tools.debugging.packets.ddms.MutableDdmsChunk:all
   #com.android.adblib.impl.services.AdbServiceRunner:all
   #com.android.adblib.tools.debugging.impl.AppProcessTrackerImpl:all
   ```

## Integrating with Visual Studio

To use `adblib` in Visual Studio, follow these steps:

1. **Add Dependency:**
   Add the `adblib` library to your project using NuGet Package Manager or by adding it to your project file.

2. **Configure Logging:**
   Set up logging in Visual Studio using a compatible logging library (e.g., log4net, NLog).

## Metrics

`adblib` allows consumers to collect various performance and reliability metrics of the underlying ADB host. This feature is currently not implemented but will be available in future releases.

## Contribution

We welcome contributions! Please fork this repository and submit pull requests. If you encounter issues, feel free to open an issue or contact the maintainers.

## Disclaimer

I am not the owner nor the maintainer of the [source code](https://android.googlesource.com/platform/tools/base/+/refs/heads/mirror-goog-studio-main/adblib/) of `adblib`. I created this repository to use the library outside of the platform tools, as there is currently no standalone release.

If you have trouble building the library, please open an issue in this repository. For other inquiries, contact the original OWNERS or report a bug in the [official bug tracker](https://issuetracker.google.com/issues?q=componentid:192708%20status:open).

## Repository

You can find the repository for this project at [https://github.com/DevOculus-Meta-Quest/AdbLib-Google-PlatformTools](https://github.com/DevOculus-Meta-Quest/AdbLib-Google-PlatformTools).
