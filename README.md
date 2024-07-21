
# `adblib` - Advanced ADB Library

![AdbLib Logo](https://example.com/logo.png) <!-- Replace with actual logo URL -->

`adblib` is a powerful Java/Kotlin library that enables programmatic access to ADB services via a socket channel. This library provides functionalities that can be invoked from the `adb` shell command, making it highly useful for various `host` environments, from simple command-line applications to complex desktop applications like Android Studio and IntelliJ IDEA.

## Quick Links

- [Overview](https://github.com/DevOculus-Meta-Quest/AdbLib-Google-PlatformTools/wiki/Overview)
- [Features](https://github.com/DevOculus-Meta-Quest/AdbLib-Google-PlatformTools/wiki/Features)
- [Installation Guide](https://github.com/DevOculus-Meta-Quest/AdbLib-Google-PlatformTools/wiki/Installation)
- [Usage Examples](https://github.com/DevOculus-Meta-Quest/AdbLib-Google-PlatformTools/wiki/Usage-Examples)
- [Integrating with Android Studio](https://github.com/DevOculus-Meta-Quest/AdbLib-Google-PlatformTools/wiki/Integrating-with-Android-Studio)
- [Integrating with Visual Studio](https://github.com/DevOculus-Meta-Quest/AdbLib-Google-PlatformTools/wiki/Integrating-with-Visual-Studio)
- [Metrics](https://github.com/DevOculus-Meta-Quest/AdbLib-Google-PlatformTools/wiki/Metrics)
- [Contribution](https://github.com/DevOculus-Meta-Quest/AdbLib-Google-PlatformTools/wiki/Contribution)
- [License](https://github.com/DevOculus-Meta-Quest/AdbLib-Google-PlatformTools/wiki/License)
- [Why We Created This Repository](https://github.com/DevOculus-Meta-Quest/AdbLib-Google-PlatformTools/wiki/Why-We-Created-This-Repository)
- [Disclaimer](https://github.com/DevOculus-Meta-Quest/AdbLib-Google-PlatformTools/wiki/Disclaimer)

## Overview

`adblib` is part of the platform tools source code used to build Android Studio or the IntelliJ IDEA IDE. You can find it in the [AOSP repository](https://android.googlesource.com/platform/tools/base/+/refs/heads/mirror-goog-studio-main/adblib/). The goal of this repository is to enable building `adblib` without cloning the entire platform repository, using non-local dependencies usually shipped with the platform tools.

For more detailed information, read the [README.origin.md](https://github.com/DevOculus-Meta-Quest/AdbLib-Google-PlatformTools/blob/main/README.origin.md).

## Features

- **Service Invocation**
- **Customizable Environments**
- **Inversion of Control**
- **Asynchronous APIs**
- **Performance Optimization**
- **Logging**
- **Metrics (TBD)**
- **Cross-Platform Support**
- **Comprehensive Error Handling**
- **ADB Wi-Fi Pairing**

For a detailed list of features, visit the [Features](https://github.com/DevOculus-Meta-Quest/AdbLib-Google-PlatformTools/wiki/Features) page in our wiki.

## Installation

For detailed installation instructions, including building with Bazel, Gradle, and using JitPack, visit our [Installation Guide](https://github.com/DevOculus-Meta-Quest/AdbLib-Google-PlatformTools/wiki/Installation).

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

Output while connecting and disconnecting a Pixel 8 phone:

```shell
### Change in state ###
### Change in state ###
Pixel_8: XXXXXXXXXXXXXX: ONLINE
### Change in state ###
Pixel_8: XXXXXXXXXXXXXX: OFFLINE
### Change in state ###
```

For more examples, visit our [Usage Examples](https://github.com/DevOculus-Meta-Quest/AdbLib-Google-PlatformTools/wiki/Usage-Examples) page.

## Integrations

### Android Studio

To integrate `adblib` with Android Studio, follow the instructions in our [Android Studio Integration Guide](https://github.com/DevOculus-Meta-Quest/AdbLib-Google-PlatformTools/wiki/Integrating-with-Android-Studio).

### Visual Studio

To integrate `adblib` with Visual Studio, follow the instructions in our [Visual Studio Integration Guide](https://github.com/DevOculus-Meta-Quest/AdbLib-Google-PlatformTools/wiki/Integrating-with-Visual-Studio).

## Metrics

`adblib` allows consumers to collect various performance and reliability metrics of the underlying ADB host. This feature is currently not implemented but will be available in future releases.

## Contribution

We welcome contributions! Please fork this repository and submit pull requests. If you encounter issues, feel free to open an issue or contact the maintainers.

## License

This project is licensed under the MIT License - see the [LICENSE](LICENSE) file for details.

## Why We Created This Repository

We created this repository to provide a standalone version of `adblib` that can be used independently of the platform tools repository. This allows developers to integrate ADB functionalities into their applications more easily without the need to clone and build the entire platform repository. Our goal is to make it simpler and more convenient to access ADB services programmatically in various development environments.

For more details, visit the [Why We Created This Repository](https://github.com/DevOculus-Meta-Quest/AdbLib-Google-PlatformTools/wiki/Why-We-Created-This-Repository) page.

## Disclaimer

I am not the owner nor the maintainer of the [source code](https://android.googlesource.com/platform/tools/base/+/refs/heads/mirror-goog-studio-main/adblib/) of `adblib`. I created this repository to use the library outside of the platform tools, as there is currently no standalone release.

If you have trouble building the library, please open an issue in this repository. For other inquiries, contact the original OWNERS or report a bug in the [official bug tracker](https://issuetracker.google.com/issues?q=componentid:192708%20status:open).

## Repository

You can find the repository for this project at [https://github.com/DevOculus-Meta-Quest/AdbLib-Google-PlatformTools](https://github.com/DevOculus-Meta-Quest/AdbLib-Google-PlatformTools).
