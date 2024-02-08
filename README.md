![Maven Central](https://img.shields.io/maven-central/v/com.jauntsdn.netty/netty-vmsocket)
[![Build](https://github.com/jauntsdn/netty-vmsocket/actions/workflows/ci-build.yml/badge.svg)](https://github.com/jauntsdn/netty-vmsocket/actions/workflows/ci-build.yml)

# netty-vmsocket

Implementation of Netty channels for VM sockets which complements [netty's vsock addresses](https://github.com/netty/netty/pull/13468) support.

### build & binaries

Binaries are compatible with java8+.

Tests require kernel 5.6+ (due to vsock loopback CID addresses).

```shell script
./gradlew clean build
```

Releases are published on MavenCentral, snapshots are available at oss.sonatype.org
```groovy
repositories {
    // mavenCentral()
    // repository {
    //     url "https://oss.sonatype.org/content/repositories/snapshots"
    // }
}

dependencies {
    implementation "com.jauntsdn.netty:netty-vmsocket:0.9.1"
}
```

### examples

`netty-vmsocket-example` showcases client and server APIs (requires kernel 5.6+ since vsock loopback CID addresses are used). 

```shell script

./gradlew clean build installDist

example_server_run.sh

example_client_run.sh
```

## LICENSE

Copyright 2023 - present Maksym Ostroverkhov.

Licensed under the Apache License, Version 2.0 (the "License");
you may not use this file except in compliance with the License.
You may obtain a copy of the License at

http://www.apache.org/licenses/LICENSE-2.0

Unless required by applicable law or agreed to in writing, software
distributed under the License is distributed on an "AS IS" BASIS,
WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
See the License for the specific language governing permissions and
limitations under the License.