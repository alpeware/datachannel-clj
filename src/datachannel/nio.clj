(ns datachannel.nio
  (:import [java.nio.channels DatagramChannel Selector SelectionKey]
           [java.net InetSocketAddress]))

(defn create-non-blocking-channel
  "Creates and binds a DatagramChannel configured for non-blocking I/O."
  ([port]
   (create-non-blocking-channel port nil))
  ([port host]
   (let [channel (DatagramChannel/open)]
     (.configureBlocking channel false)
     (if host
       (.bind (.socket channel) (InetSocketAddress. host port))
       (.bind (.socket channel) (InetSocketAddress. port)))
     channel)))

(defn create-selector
  "Opens a new NIO Selector."
  []
  (Selector/open))

(defn register-for-read
  "Registers the given channel with the selector for OP_READ operations."
  [^DatagramChannel channel ^Selector selector]
  (.register channel selector SelectionKey/OP_READ))

(defn connect-channel
  "Connects a DatagramChannel to a specified host and port."
  [^DatagramChannel channel host port]
  (.connect channel (InetSocketAddress. host port)))
