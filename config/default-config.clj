(ns witchazzan.common
  (:gen-class))
; we're storing settings in a map, add ':index value' on a line to add settings
; retrieve with (:index settings)
(def settings (atom
               {:port 8080
                :tilemap-path "../witchazzan-client/src/assets/tileMaps/"
                :tilemaps '("LoruleF8.json" "LoruleG7.json" "LoruleG8.json" "LoruleH6.json" "LoruleH7.json" "LoruleH8.json" "LoruleI7.json" "LoruleI8.json" "LoruleJ8.json" "arena1.json" "testScene1.json")
                :millis-per-frame 40
                :pause false
                :auto-save true
                :auto-load true
                :gene-max 255
                :millis-per-hour 60000}))
(defn setting
  ([key value]
   (swap! settings #(merge % {(keyword key) value})))
  ([key] ((keyword key) @settings)))
;add custom settings to config/config.clj with
;(setting "key" value)
;if your setup is entirely standard, no settings are required
