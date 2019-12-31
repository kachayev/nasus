A simple zero-configuration command-line HTTP files server. Like Python's `SimpleHTTPServer` but scalable. Сan easily handle thousands of simultaneous connections.

Implemented in `Clojure` with [`Aleph`](https://github.com/ztellman/aleph) and [`Netty`](https://github.com/netty/netty). Mostly as an example. It's still skillful and handy tho'. There's even nothing wrong with putting it to production.

## Usage

Run in the directory you want to serve:

```shell
clj -Sdeps '{:deps {nasus {:mvn/version "0.1.5"}}}' -m http.server
```

Or specify custom port:

```shell
clj -Sdeps '{:deps {nasus {:mvn/version "0.1.5"}}}' -m http.server 8001
```

## Features

* Plain text & HTML directory listings based on "Accept" header
* Automatic mime-type detection
* Streaming and chunked encoding for large files
* Keep-alive and slow requests handling
* Transparent content compression (gzip, deflate)
* Cache control and "Last-Modified"
* CORS headers

In development:

* Range queries support
* SSL/TLS
* List of files & directories to exclude from serving

## Flags

```
  -p, --port <PORT>         8000        Port number
  -b, --bind <IP>           0.0.0.0     Address to bind to
      --dir <PATH>          ./          Directory to serve files
      --auth <USER[:PASSWORD]>          Basic auth
      --no-index                        Disable directory listings
      --index                           Return --index-doc instead of a directory listing
      --index-doc <PATH>                The file to use with --index
      --no-cache                        Disable cache headers
      --no-compression                  Disable deflate and gzip compression
      --follow-symlink                  Enable symbolic links support
      --include-hidden                  Process hidden files as normal
      --cors                            Support Acccess-Control-* headers, see --cors-* options for more fine-grained control
      --cors-origin                     Acccess-Control-Allow-Origin response header value
      --cors-methods                    Acccess-Control-Allow-Methods response header value
      --cors-allow-headers              Acccess-Control-Allow-Headers response header value
      --default                         Serve --default-doc instead of a directory listing
      --default-doc <PATH>  index.html  The file to use with --default
  -h, --help
```

## License

Copyright © 2019 Nasus

Nasus is licensed under the MIT license, available at MIT and also in the LICENSE file.
