A simple zero-configuration command-line HTTP files server. Like Python's `SimpleHTTPServer` but scalable. Сan easily handle thousands of simultaneous connections.

Implemented in `Clojure` with [`Aleph`](https://github.com/ztellman/aleph) and [`Netty`](https://github.com/netty/netty). Mostly as an example. It's still skillful and handy tho'. There's even nothing wrong with putting it to production.

## Usage

Run in the directory you want to serve:

```shell
clj -Sdeps '{:deps {nasus {:mvn/version "0.1.2"}}}' -m http.server
```

Or specify custom port:

```shell
clj -Sdeps '{:deps {nasus {:mvn/version "0.1.2"}}}' -m http.server 8001
```

## Features

* Plain text & HTML directory listings based on "Accept" header
* Automatic mime-type detection
* Streaming and chunked encoding for large files
* Keep-alive and slow requests handling
* Transperent content compression (gzip, deflate)
* Cache control and "Last-Modified

In development:

* Conditional requests: "ETag", "If-None-Match"
* Range queries support
* SSL/TLS
* Basic auth
* List of files & directories to exclude from serving

## Flags

```
  -p, --port <PORT>  8000     Port number
  -b, --bind <IP>    0.0.0.0  Address to bind to
      --no-index              Disable directory listings
      --no-cache              Disable cache headers
  -h, --help
```

## License

Copyright © 2019 Nasus

Nasus is licensed under the MIT license, available at MIT and also in the LICENSE file.

## Nasus

![Nasus](https://github.com/kachayev/nasus/blob/master/docs/logo/nasus.jpg)
