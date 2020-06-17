;;namespace
(ns witchazzan.behavior
  (:require [witchazzan.common :refer :all])
  (:gen-class))
;;namespace
;;game-piece creation helpers
;normalize settings values
(def hunger-constant (/ (setting :hunger) (setting :millis-per-hour)))
(def photosynthesis-constant (/ (setting :photosynthesis) (setting :millis-per-hour)))

(defn normalize-genes
  "prevent mutation from moving genes outside the 0-gene-max range"
  [genes]
  (zipmap (keys genes)
          (map
           #(cond (> % (setting "gene-max"))
                  (- % (setting "gene-max"))
                  (< % 0)
                  (+ % (setting "gene-max"))
                  :else %)
           (vals genes))))

(defn mutate-genes
  "each gene can be incremeneted, decremented or stay the same with equal chance"
  [genes]
  (normalize-genes
   (zipmap (keys genes)
           (map #(+ (- (rand-int 3) 1) %) (vals genes)))))

(defn generate-genes
  "makes a list of keywords into a map of ints, arbitrarily limited by settings"
  [& keywords]
  (zipmap keywords
          (repeatedly #(rand-int (+ 1 (setting "gene-max"))))))

(defn teleport
  "check for and apply teleports"
  [this]
  (let [scene (name->scene (:scene this))
        ^clojure.lang.LazySeq tp-collisions
        (map #(get (get % "data") (+ (int (:x this)) (* (:width scene) (int (:y this))))) (:teleport scene))]
    (cond
      (and
       (some #(not (= 0 %)) tp-collisions))
      (let [target (nth (:teleport scene) (.indexOf tp-collisions (apply max tp-collisions)))
            tilewidth (:tilewidth scene)
            target-obj-name (get (first (get target "properties")) "value")
            target-obj
            (or
             (ffilter ; intended entrance
              #(= (get % "name") target-obj-name)
              (:objects (name->scene (get target "name"))))
             (ffilter ; backup entrance.
              #(= (get % "name") "Default Spawn Point")
              (:objects (name->scene (get target "name")))))]
        (cond
          target-obj
          (merge this
                 {:x (/ (get target-obj "x") tilewidth)
                  :y (/ (get target-obj "y") tilewidth)
                  :scene (get target "name")})
          :else (merge this (find-empty-tile (:scene this)))))
        ;if we can't find a target-obj, the scene we want doesn't exist.
      :else this)))
;;helpers
;;shared behavior
(def init-piece)
(defn add-game-piece!
  [piece]
  (let [new-piece (agent piece)]
    (swap! game-state
           (fn [state] (update-in state [:game-pieces]
                                  (fn [game-pieces] (merge game-pieces new-piece)))))
    (send new-piece init-piece)))

(defn delete
  [this]
  (swap! game-state
         (fn [state] (update-in state [:game-pieces]
                                (fn [game-pieces]
                                  (filterv
                                   #(not (= (:id this) (:id @%)))
                                   game-pieces))))))

(defn shift
  [this]
  (let [collisions
        (typed-pieces (class this) {:scene (:scene this) :x (:x this) :y (:y this)})]
    (cond
      (and
       (>= (count collisions) 2)
       (<
        (:energy this)
        (reduce max
                (map #(:energy @% -1) collisions))))
      (merge this (find-empty-tile (:scene this)))
      :else
      this)))
;;shared behavior
;;defprotocol

(defprotocol game-piece
  (behavior [this])
  (die [this])
  (reproduce [this])
  (init-piece [this]))

(defn hunger
  [this]
  (when (< (:energy this) 0) (die this))
  (merge this {:energy (- (:energy this) (* (:delta this) hunger-constant))}))

(defn hit-points
  [this]
  (cond
    (< (:health this) 1)
    (delete this)
    :else
    this))

(defn sunny?
  [this]
  (and
   (> (:hour @game-state) (setting "dawn"))
   (< (:hour @game-state) (setting "sunset"))
   (not (re-seq #"Cave" (:scene this)))))

(defn crowded?
  [this]
  (let [this-scene (typed-pieces (class this) {:scene (:scene this)})]
    (seq (filter
          #(and
            (not (= (:id this) (:id @%)))
            (within-n (:x @%) (:x this) (setting :crowding-factor))
            (within-n (:y @%) (:y this) (setting :crowding-factor)))
          this-scene))))

(defn photosynthesis
  [this]
  (cond
    (and (sunny? this) (not (crowded? this)))
    (merge this {:energy (+ (:energy this) (* (:delta this) photosynthesis-constant))})
    :else this))

(defn carrot-repro-decide
  [this]
  (cond
    (> (:energy this) (:repro-threshold (:genes this)))
    (reproduce this)
    :else this))

(defrecord carrot
           [id
            genes
            energy
            scene
            sprite
            milliseconds
            health
            x
            y
            parent-id
            type]
  game-piece
  (init-piece
    [this]
    (if (> (:leech-seed (:genes this)) 200)
      (merge this {:leech-seed true})
      this))
  (die
    [this]
    (delete this)
    nil)
  (behavior
    [this]
    (let [time (System/currentTimeMillis)
          delta (- time milliseconds)]
      (-> this
          (merge {:milliseconds time})
          (merge {:delta delta})
          (photosynthesis)
          (shift)
          (hunger)
          (hit-points)
          (carrot-repro-decide)
          (teleport))))
  (reproduce
    [this]
    (let [energy (/ energy 3)
          tile (find-empty-tile scene)
          genes (mutate-genes genes)]
      (add-game-piece!
       (map->carrot (into {} (merge this
                                    {:genes genes
                                     :x (:x tile)
                                     :y (:y tile)
                                     :energy energy
                                     :parent-id (:id this)
                                     :id (gen-id)}))))
      (merge this {:energy energy}))))

(defn spell-terrain-collide [this]
  (cond
    (not ((:get-tile-walkable (name->scene (:scene this))) this))
    (delete this)
    :else this))

(defn spell-object-collide [this]
  (let [collisions (filter
                    #(and (not= (:id this) (:id @%)) (not= (:owner-id this) (:id @%)))
                    (active-pieces {:x (:x this) :y (:y this) :scene (:scene this)}))]
    (cond
      (seq collisions)
      (do
        (when (= "fireball" (:spell this))
          (run! (fn [that] (send that merge {:health (- (:health @that 1) 1)})) collisions))
        (when (= "teleball" (:spell this))
          (run! (fn [that] (send that merge (find-empty-tile (:scene this)) {:force true})) collisions))
        (delete this))
      :else this)))

(defrecord spell
           [id
            x
            y
            spell
            sprite
            speed
            scene
            direction
            milliseconds
            owner-id
            type]
  game-piece
  (init-piece
    [this]
    this)
  (behavior
    [this]
    (let [time (System/currentTimeMillis)
          delta (- time milliseconds)]
      (-> this
          (merge {:milliseconds time})
          (merge
           (cond
             (= (:direction this) "up")
             {:y (- y (* speed delta))}
             (= (:direction this) "down")
             {:y (+ y (* speed delta))}
             (= (:direction this) "left")
             {:x (- x (* speed delta))}
             (= (:direction this) "right")
             {:x (+ x (* speed delta))}))
          (spell-terrain-collide)
          (spell-object-collide)
          (teleport)))))

(defn cast-spell
  [this]
  (let [spell (:spell this)]
    (cond
      (or (= "teleball" spell) (= "fireball" spell))
      (add-game-piece! (map->spell {:id (gen-id)
                                    :x (:x this)
                                    :y (:y this)
                                    :type "spell"
                                    :spell spell
                                    :scene (:scene this)
                                    :sprite spell
                                    :speed 0.01
                                    :direction (:direction this)
                                    :milliseconds (System/currentTimeMillis)
                                    :owner-id (:id this)})))
    (merge this {:spell nil})))

(defrecord player
           [id
            socket
            x
            y
            name
            health
            sprite
            milliseconds
            type]
  game-piece
  (init-piece
    [this]
    this)
  (behavior
    [this]
    (let [time (System/currentTimeMillis)
          delta (- time milliseconds)]
      (-> this
          (cast-spell)))))

;ok, how do carrots handle being crowded?
;one genne determines the size of it's territory.
;A higher number means it gets effected by more distand carrots.
;A higher number ALSO means higher photosynthesis yield.
;Then we want some strategies for handling incoming damage.
;1, lower both of your energy by the (min en1 en2) and kill competitors
;at the risk of starving?
;2, parasite, sap sone energy every turn?
;3 maybe a more cooperative option? How about a carrot that just takes reduced
;penalty for having nearby carrot?
;Not sure how these are for game balance but it would at least
;be more interesting


(defn spawn-carrot [& coords]
  (let [scene (or (:scene (into {} coords)) "LoruleH8")
        coords (if (seq coords) (into {} coords) (find-empty-tile scene))]
    (cond
      coords
      (add-game-piece!
       (map->carrot
        {:id (gen-id)
         :genes (generate-genes :repro-threshold :leech-seed :color-r :color-g :color-b)
         :energy 20
         :scene scene
         :sprite "carrot"
         :milliseconds (System/currentTimeMillis)
         :x (:x coords)
         :y (:y coords)
         :parent-id -1
         :health 1
         :type "carrot"}))
      :else (log "spawn-carrot failed to find an empty tile"))))
