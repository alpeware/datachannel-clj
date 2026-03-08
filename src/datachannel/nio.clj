(ns datachannel.nio
  (:import [java.nio.channels DatagramChannel Selector SelectionKey]
           [java.net InetSocketAddress]))

(defn create-non-blocking-channel
  "Creates and binds a DatagramChannel configured for non-blocking I/O."
  [port]
  (let [channel (DatagramChannel/open)]
    (.configureBlocking channel false)
    (.bind channel (InetSocketAddress. (int port)))
    channel))

(defn create-selector
  "Opens a new NIO Selector."
  []
  (Selector/open))

(defn register-for-read
  "Registers the given channel with the selector for OP_READ operations."
  [^DatagramChannel channel ^Selector selector]
  (.register channel selector SelectionKey/OP_READ))

(defn connect-channel
  "Connects a DatagramChannel to the specified host and port."
  [^DatagramChannel channel host port]
  (.connect channel (InetSocketAddress. ^String host (int port))))
