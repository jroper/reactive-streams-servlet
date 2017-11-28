# Servlet 3.1 Reactive Streams implementation

This is a reactive streams implementation based on the Servlet 3.1 asynchronous IO API. It has zero dependencies, other than the JDK. It is TCK verified.

## Usage

To create a publisher for a request (which can be subscribed to to consume the request body):

```java
HttpServletRequest request = ...
AsyncContext context = request.startAsync();
Publisher<ByteBuffer> publisher = new RequestPublisher(context, 8192);
```

The second parameter to `RequestPubisher` is the maximum amount of data to read on each attempt to read.

To create a subscriber for a response (which can be passed to a publisher to publish the response body):

```java
HttpServletRequest request = ...
AsyncContext context = request.startAsync();
Subscriber<ByteBuffer> subscriber = new ResponseSubscriber(context);
```

## Status

Currently, the response subscriber only requests one element at a time, and never buffers. This could be improved, for example, using a low and high watermark based buffer, to keep a few elements in flight for maximising throughput.

## Legal

This project has been produced by Lightbend. The code is offered to the Public Domain in order to allow free use by interested parties who want to create compatible implementations. For details see `COPYING`.

<p xmlns:dct="http://purl.org/dc/terms/" xmlns:vcard="http://www.w3.org/2001/vcard-rdf/3.0#">
  <a rel="license" href="http://creativecommons.org/publicdomain/zero/1.0/">
    <img src="http://i.creativecommons.org/p/zero/1.0/88x31.png" style="border-style: none;" alt="CC0" />
  </a>
  <br />
  To the extent possible under law,
  <a rel="dct:publisher" href="http://www.reactive-streams.org/">
    <span property="dct:title">Reactive Streams Special Interest Group</span></a>
  has waived all copyright and related or neighboring rights to
  <span property="dct:title">Reactive Streams JVM</span>.
  This work is published from:
  <span property="vcard:Country" datatype="dct:ISO3166" content="US" about="http://www.reactive-streams.org/">United States</span>.
</p>