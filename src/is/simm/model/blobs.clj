(ns is.simm.model.blobs
  "DELEGATES to dvergr.drive.blobs (the CAS substrate lifted into dvergr).
   The store is pointed at simmis's existing path (data/simmis-blobs) via
   set-store-config! at web boot, so previously stored blobs keep resolving."
  (:require [dvergr.drive.blobs :as dblobs]))

(def sha256-hex dblobs/sha256-hex)
(def store!     dblobs/store!)
(def get-bytes  dblobs/get-bytes)
