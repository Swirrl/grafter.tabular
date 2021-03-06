(ns grafter-2.tabular
  "Functions for processing tabular data."
  (:require [clojure
             [set :as set]
             [string :as str]]
            [grafter-2.tabular.common :as tabc :refer [lift->vector map-keys]]
            [incanter.core :as inc]
            [potemkin.namespaces :refer [import-vars]])
  (:import incanter.core.Dataset))

;; Load protocol definitions.  This could occur in the ns definition but putting
;; them in their means that namespace refactoring tools can clear them out
;; accidentally.  Better to explicitly require them to ensure they're loaded.
(require '[grafter-2.tabular
           [csv]
           [excel]])

;; This one is necessary for import-vars - again separating these from the ns
;; definition protects them against overzealous refactoring tools.
(require '[grafter-2.tabular.melt])

(import-vars
 [grafter-2.tabular.common
  dataset?
  column-names
  make-dataset
  move-first-row-to-header
  read-dataset
  read-datasets
  write-dataset
  with-metadata-columns
  without-metadata-columns
  resolve-column-id]
 [grafter-2.tabular.melt
  melt])



(defn test-dataset
  "Constructs a test dataset of r rows by c cols e.g.

`(test-dataset 2 2) ;; =>`

| A | B |
|---|---|
| 0 | 0 |
| 1 | 1 |"
  [r c]
  (->> (iterate inc 0)
       (map #(repeat c %))
       (take r)
       make-dataset))

(defn- invalid-column-keys
  "Takes a sequence of column key names and a dataset and returns a
  sequence of keys that are not in the dataset."
  [dataset keys]

  (let [not-found (Object.)
        not-found-items (->> keys
                             (map (fn [col]
                                    [col (resolve-column-id dataset col not-found)]))
                             (filter (fn [[_ present]] (= not-found present)))
                             (map first))]
    not-found-items))

(defn- resolve-all-col-ids [dataset source-cols]
  (map (partial resolve-column-id dataset) source-cols))

(defn- all-columns
  "Takes a dataset and a finite sequence of column identifiers.

  If you want to use infinite sequences of columns or allow the
  specification of more cols than are in the data without error you
  should use columns instead.  Using an infinite sequence with this
  function will result in non-termination.

  Unlike the columns function this function will raise an
  IndexOutOfBoundsException if a specified column is not actually
  found in the Dataset."
  [dataset cols]
  (let [original-meta (meta dataset)
        not-found-items (invalid-column-keys dataset cols)]
    (if (and (empty? not-found-items)
             (some identity cols))
      (let [resolved-cols (resolve-all-col-ids dataset cols)
            rows (->> dataset :rows (map #(select-keys % resolved-cols)))]
        (with-meta (make-dataset rows resolved-cols) original-meta))
      (throw (IndexOutOfBoundsException. (str "The columns: "
                                              (str/join ", " not-found-items)
                                              " are not currently defined."))))))

(defn- indexed [col]
  (map-indexed vector col))

(defn- rows-bounded [row-data row-numbers]
  (let [row-numbers (into #{} row-numbers)]
    (->> row-data
         (filter (fn [[index row]]
                   (if (row-numbers index)
                     true
                     false)))
         (map second))))

(defn- select-indexed
  "Selects indexed rows or columns (outside of the dataset).  Assumes the seq of
  row-numbers to select on is ordered, and that row-data is a tuple
  of form `[index row]`.

  Returns a lazy sequence of matched rows."
  [[[index current-item] & item-data]
   [current-item-number & rest-item-numbers :as item-numbers]]
  (cond
   (or (nil? current-item-number)
       (nil? index)
       (= ::not-found current-item-number)) []

       (= current-item-number index) (let [[repeated-item-numbers remaining-item-numbers]
                                           (split-with #(= current-item-number %) item-numbers)
                                           repeated-items (repeat (count repeated-item-numbers) current-item)]
                                       (lazy-cat
                                         repeated-items
                                         (select-indexed item-data remaining-item-numbers)))

       (< current-item-number index) (select-indexed
                                       (drop-while (fn [[index item]]
                                                     (not= index current-item-number))
                                                   item-data)
                                       rest-item-numbers)
       (> current-item-number index) (select-indexed
                                       (drop-while (fn [[index item]]
                                                     (not= index current-item-number))
                                                   item-data)
                                       ;; leave item-numbers as is (i.e. stay on current item after fast forwarding the data)
                                       item-numbers)))

(defn rows
  "Takes a dataset and a seq of row-numbers and returns a dataset
  consisting of just the supplied rows.  If a row number is not found
  the function will assume it has consumed all the rows and return
  normally."
  [dataset row-numbers]
  (let [original-meta (meta dataset)
        original-columns (column-names dataset)
        rows (indexed (tabc/to-list dataset))
        filtered-rows (select-indexed rows row-numbers)]

    (-> (make-dataset filtered-rows
                      original-columns)
        (with-meta original-meta))))

;; This type hint is actually correct as APersistentVector implements .indexOf
;; from java.util.List.
(defn- col-position [^java.util.List column-names col]
  (if-let [canonical-col (tabc/resolve-col-id col column-names ::not-found)]
    (let [val (.indexOf column-names canonical-col)]
      (if (not= -1 val)
        val
        ::not-found))))

(defn- elided-col-description
  "Print elided descriptions of columns for error messages with a sample set and
  the rest hidden behind an elipsis, e.g. \":one, :two, :three ...\""
  [coll]
  (let [[examples more] (take 2 (partition-all 3 coll))
        csv (str/join ", " examples)
        ellision (when (seq more)
                   " ...")]
    (str csv ellision)))

(defn columns
  "Given a dataset and a sequence of column identifiers, columns
  narrows the dataset to just the supplied columns.

  Columns specified in the selection that are not included in the
  Dataset will be silently ignored.

  The order of the columns in the returned dataset will be determined
  by the order of matched columns in the selection.

  The supplied sequence of columns are first cropped to the number of
  columns in the dataset before being selected, this means that
  infinite sequences can safely supplied to this function."
  [dataset cols]
  (let [col-names (column-names dataset)
        max-cols (count (:column-names dataset))
        restrained-cols (take max-cols cols)
        matched-col-positions (->> restrained-cols
                                   (map (partial col-position col-names)))
        valid-positions (filterv #(not= ::not-found %) matched-col-positions)
        selected-cols (map #(nth col-names %) valid-positions)]
    (if (seq selected-cols)
      (all-columns dataset selected-cols)
      (throw (IndexOutOfBoundsException. (str "The columns: "
                                              (elided-col-description cols)
                                              " are not currently defined."))))))

(defn rename-columns
  "Renames the columns in the dataset.  Takes either a map or a
  function.  If a map is passed it will rename the specified keys to
  the corresponding values.

  If a function is supplied it will apply the function to all of the
  column-names in the supplied dataset.  The return values of this
  function will then become the new column names in the dataset
  returned by rename-columns."
  [dataset col-map-or-fn]
  {:pre [(or (map? col-map-or-fn)
             (ifn? col-map-or-fn))]}

  (if (map? col-map-or-fn)
    (rename-columns dataset (fn [col] (col-map-or-fn col col)))
    (let [original-meta (meta dataset)
          old-key->new-key (partial map-keys col-map-or-fn)
          new-columns (map col-map-or-fn
                           (column-names dataset))]

      (-> (make-dataset (tabc/to-list dataset)
                        new-columns)
          (with-meta original-meta)))))

(defn reorder-columns
  "Reorder the columns in a dataset to the supplied order. An error
  will be raised if the supplied set of columns are different to the
  set of columns in the dataset."
  [{:keys [column-names] :as ds} cols]
  (let [ds-cols (set column-names)
        supplied-cols (map (partial tabc/resolve-column-id ds) cols)]

    (when (not= ds-cols (set supplied-cols))
      (throw (ex-info (str "The set of supplied column names " supplied-cols
                           " must be equal to those in the dataset " ds-cols
                           " to reorder.")
                      {:type :reorder-columns-error
                       :dataset-columns column-names
                       :supplied-columns supplied-cols})))
    (assoc ds :column-names supplied-cols)))

(defn drop-rows
  "Drops the first n rows from the dataset, retaining the rest."
  [dataset n]
  (tabc/pass-rows dataset (partial drop n)))

(defn take-rows
  "Takes only the first n rows from the dataset, discarding the rest."
  [dataset n]
  (tabc/pass-rows dataset (partial take n)))

(defn- resolve-keys [headers hash]
  (map-keys #(tabc/resolve-col-id % headers nil) hash))

(defn- select-row-values [src-col-ids row]
  (map #(get row %) src-col-ids))

(defn- apply-f-to-row-hash [src-col-ids new-header f row]
  (let [args-from-cols (select-row-values src-col-ids row)
        new-col-val (apply f args-from-cols)
        new-column-hash (resolve-keys new-header new-col-val)]
    (merge row new-column-hash)))

(defn derive-column
  "Adds a new column to the end of the row which is derived from
column with position col-n.  f should just return the cells value.

If no f is supplied the identity function is used, which results in
the specified column being cloned."

  ([dataset new-column-name from-cols]
     (derive-column dataset new-column-name from-cols identity))

  ([dataset new-column-name from-cols f]
   (let [original-meta (meta dataset)
         original-columns (column-names dataset)
         from-cols (lift->vector from-cols)
         resolved-from-cols (resolve-all-col-ids dataset from-cols)]

     (-> (make-dataset (->> dataset
                            :rows
                            (map (fn [row]
                                   (let [args-from-cols (select-row-values resolved-from-cols row)
                                         new-col-val (apply f args-from-cols)]
                                     (assoc row new-column-name new-col-val)))))
                       (concat original-columns [new-column-name]))
         (with-meta original-meta)))))

(defn add-column
  "Add a new column to a dataset with the supplied value lazily copied
  into every row within it."

  [dataset new-column value]
  (let [ignored-column-id 0]
    ;; all real datasets have a 0th column but grafter doesn't
    ;; currently work with empty 0x0 datasets.  We should support this
    ;; case.
    ;;
    ;; TODO when we support these: https://trello.com/c/cdmlw7Xv we
    ;; should update this code to work with empty datasets too.
    (derive-column dataset new-column ignored-column-id (constantly value))))

(defn- infer-new-columns-from-first-row [dataset source-cols f]
  (let [source-cols (resolve-all-col-ids dataset source-cols)
        first-row-values (->> dataset
                              :rows
                              first
                              (select-row-values source-cols))
        first-result (apply f first-row-values)
        new-col-ids (keys first-result)]

    new-col-ids))

(defn add-columns
  "Add several new columns to a dataset at once.  There are a number of different parameterisations:

  `(add-columns ds {:foo 10 :bar 20})`

  Calling with two arguments where the second argument is a hash map
  creates new columns in the dataset for each of the hashmaps keys and
  copies the hashes values lazily down all the rows.  This
  parameterisation is designed to work well build-lookup-table.

  When given either a single column id or many along with a function
  which returns a hashmap, add-columns will pass each cell from the
  specified columns into the given function, and then associate its
  returned map back into the dataset.  e.g.

  `(add-columns ds \"a\" (fn [a] {:b (inc a) :c (inc a)} )) ; =>`

  | a | :b | :c |
  |---|----|----|
  | 0 |  1 |  1 |
  | 1 |  2 |  2 |

  As a dataset needs to know its columns in this case it will infer
  them from the return value of the first row.  If you don't want to
  infer them from the first row then you can also supply them like so:

  `(add-columns ds [:b :c] \"a\" (fn [a] {:b (inc a) :c (inc a)} )) ; =>`

  | a | :b | :c |
  |---|----|----|
  | 0 |  1 |  1 |
  | 1 |  2 |  2 |"

  ([dataset hash]
     (let [merge-cols (fn [ds k]
                        (add-column ds k (hash k)))
           keys (-> hash keys sort)]
       ;; Yes, this is actually lazy with respect to rows, as we're
       ;; just reducing new lazy columns onto our dataset.
       (reduce merge-cols dataset keys)))

  ([dataset source-cols f]
     (let [source-cols (lift->vector source-cols)
           new-col-ids (infer-new-columns-from-first-row dataset source-cols f)]
       (add-columns dataset new-col-ids source-cols f)))

  ([dataset new-col-ids source-cols f]
     (let [original-meta (meta dataset)
           source-cols (lift->vector source-cols)
           new-header (concat (:column-names dataset) new-col-ids)
           col-ids (resolve-all-col-ids dataset source-cols)
           apply-f-to-row (partial apply-f-to-row-hash col-ids new-header f)]

       (-> (make-dataset (map apply-f-to-row (:rows dataset))
                         new-header)
           (with-meta original-meta)))))

(defn- grep-row [dataset f]
  (let [original-meta (meta dataset)
        filtered-data (filter f (:rows dataset))]
    (-> (make-dataset filtered-data
                      (column-names dataset))
        (with-meta original-meta))))

(defmulti grep
  "Filters rows in the table for matches.  This is multi-method
  dispatches on the type of its second argument.  It also takes any
  number of column numbers as the final set of arguments.  These
  narrow the scope of the grep to only those columns.  If no columns
  are specified then grep operates on all columns."
  (fn [table f & cols] (class f)))

(defn- cells-from-columns
  "Returns a seq of cells matching the supplied columns, cells are
  stripped of column names by this process.  If no columns are specified all the cell
  values for the row are returned."
  [col-set row]
  (->> row
       (filter (fn [[k v]] (col-set k)))
       (map second)))

(defmethod grep clojure.lang.IFn

  [dataset f & cols]
  (let [original-meta (meta dataset)
        data (:rows dataset)
        cols (if (nil? cols)
                 (column-names dataset)
                 (first cols))
        col-set (into #{} cols)]

    (-> (make-dataset (->> data
                           (filter (fn [row]
                                     (some f
                                           (cells-from-columns col-set row)))))
                      (column-names dataset))
        (with-meta original-meta))))

(defmethod grep java.lang.String [dataset s & cols]
  (apply grep dataset (fn [^String cell] (.contains (str cell) s)) cols))

(defmethod grep java.util.regex.Pattern [dataset p & cols]
  (apply grep dataset #(re-find p (str %)) cols))

;; grep with a sequence of integers is equivalent to using rows
(defmethod grep clojure.lang.Sequential [dataset row-numbers]
  (rows dataset row-numbers))

(prefer-method grep clojure.lang.Sequential clojure.lang.IFn)

(defmethod grep :default [dataset v & cols]
  (apply grep dataset (partial = v) cols))

(defn- remove-indices [col & idxs]
  "Removes the values at the supplied indexes from the given vector."
  (let [pos (map - (sort idxs) (iterate inc 0))
        remove-index (fn [col pos]
                       (vec (concat (subvec col 0 pos)
                                    (subvec col (inc pos)))))]
    (reduce remove-index col pos)))

(def _ "An alias for the identity function, used for providing positional arguments to mapc." identity)

(defn- normalise-mapping
  "Given a dataset and a map/vector mapping ids or positions to
  values.  Return a map with normalised keys that map to the
  appropriate values.  A normalised mapping will contain identity
  mappings for any ommitted columns."
  [dataset fs]
  (let [resolve-ids (fn [id] (resolve-column-id dataset id id))
        fs-hash (if (vector? fs)
                  (zipmap (column-names dataset) fs)
                  (map-keys resolve-ids fs))
        other-hash (zipmap (vec (set/difference (set (:column-names dataset))
                                                (set (keys fs-hash))))
                           (repeat identity))
        functions (conj fs-hash other-hash)]

    functions))

(defn- concat-new-columns
  "Given a dataset and a set of column keys return an ordered vector
  of the new column keys.

  Any new column ids will be concatenated onto the end of the existing
  columns preserving the order as best as possible.

  Any duplicate ids found in col-ids will be removed."

  [dataset col-ids]
  (let [existing-column-ids (:column-names dataset)
        resolve-new-ids (fn [i] (resolve-column-id dataset i i))]

    (concat existing-column-ids
            (remove (set existing-column-ids) (map resolve-new-ids col-ids)))))

(defn mapc
  "Takes a vector or a hashmap of functions and maps each to the key
  column for every row.  Each function should be from a cell to a
  cell, where as with apply-columns it should be from a column to a
  column i.e. its function from a collection of cells to a collection
  of cells.

  If the specified column does not exist in the source data a new
  column will be created, though the supplied function will need to
  either ignore its argument or handle a nil argument."
  [dataset fs]
  (let [original-meta (meta dataset)
        functions (normalise-mapping dataset fs)
        new-columns (concat-new-columns dataset (keys functions))
        apply-functions (fn [row] ;; TODO consider using zipmap to do this job
                          (let [apply-column-f (fn [[col-id f]]
                                                 (let [fval (f (row col-id))]
                                                   {col-id fval}))]
                            (apply merge (map apply-column-f
                                              functions))))]

    (-> (make-dataset (->> dataset :rows (map apply-functions))
                      new-columns)
        (with-meta original-meta))))

(defn apply-columns
  "Like mapc in that you associate functions with particular columns,
  though it differs in that the functions given to mapc should receive
  and return values for individual cells.

  With apply-columns, the function receives a collection of cell
  values from the column and should return a collection of values for
  the column.

  It is also possible to create new columns with apply-columns for
  example to assign row ids you can do:

  `(apply-columns ds {:row-id (fn [_] (grafter.sequences/integers-from 0))})`"
  [dataset fs]
  (let [original-meta (meta dataset)
        functions (normalise-mapping dataset fs)
        new-columns (concat-new-columns dataset (keys functions))
        apply-columns-f (fn [rows]
                          ;; TODO consider implementing this in
                          ;; terms of either incanter.core/to-map
                          ;; or zipmap
                          (let [apply-to-cols (fn [[col f]]
                                                (->> rows
                                                     (map (fn [r] (get r col)))
                                                     f
                                                     (map (fn [r] {col r}))))]
                            (->> functions
                                 (map apply-to-cols)
                                 (apply (partial map merge)))))]

    (-> (make-dataset (->> dataset :rows apply-columns-f)
                      new-columns)
        (with-meta original-meta))))

(defn swap
  "Takes an even numer of column names and swaps each column"

  ([dataset first-col second-col]
   (let [original-meta (meta dataset)
         data (:rows dataset)
         header (column-names dataset)
         swapper (fn [v i j]
                   (-> v
                       (assoc i (v j))
                       (assoc j (v i))))]

     (-> (make-dataset data
                       (-> header
                           (swapper (col-position header first-col)
                                    (col-position header second-col))))
         (with-meta original-meta))))

  ([dataset first-col second-col & more]
   (if (even? (count more))
     (if (seq more)
       (reduce (fn [ds [f s]]
                 (swap ds f s))
               (swap dataset first-col second-col)
               (partition 2 more))
       (swap dataset first-col second-col))
     (throw (Exception. "Number of columns should be even")))))

(defn- remaining-keys [dataset key-cols]
  (let [remaining-keys (->> key-cols
                            (set/difference (set (:column-names dataset))))]

    remaining-keys))

(defn- order-values [key-cols hash]
  (map #(get hash %) key-cols))

(defn resolve-key-cols [dataset key-cols]
  (->> (set (lift->vector key-cols))
       (order-values key-cols)
       (resolve-all-col-ids dataset)))

(defn build-lookup-table
  "Takes a dataset, a vector of any number of column names corresponding
  to key columns and a column name corresponding to the value
  column.
  Returns a function, taking a vector of keys as
  argument and returning the value wanted"
  ([dataset key-cols]
     (build-lookup-table dataset key-cols nil))

  ([dataset key-cols return-keys]
     (let [key-cols (resolve-key-cols dataset (lift->vector key-cols))
           return-keys (resolve-all-col-ids dataset
                                            (if (nil? return-keys)
                                              (remaining-keys dataset key-cols)
                                              (lift->vector return-keys)))

           keys (->> (all-columns dataset key-cols)
                     :rows
                     (map (fn [hash]
                            (let [v (vals hash)]
                              (if (= (count v) 1)
                                (first v)
                                ;; else return them in key-col order
                                (order-values key-cols hash))))))
           val (:rows (all-columns dataset return-keys))
           table (zipmap keys val)]
       table)))

(defn ^:no-doc get-column-by-number*
  "This function is intended for use by the graph-fn macro only, and
  should not be considered part of this namespaces public interface.
  It is only public because it is used by a macro."
  [ds row index]
  (let [col-name (grafter-2.tabular/resolve-column-id ds index ::not-found)]
    (if-not (= col-name ::not-found)
      (get row col-name ::not-found))))

(defn- generate-vector-bindings [ds-symbol row-symbol row-bindings]
  (let [bindings (->> row-bindings
                      (map-indexed (fn [index binding]
                                     [binding `(get-column-by-number* ~ds-symbol ~row-symbol ~index)]))
                      (apply concat)
                      (apply vector))]
    bindings))

(defn- splice-supplied-bindings [row-sym row-bindings]
  `[~row-bindings ~row-sym])

(defmacro graph-fn
  "A macro that defines an anonymous function to convert a tabular
  dataset into a graph of RDF quads.  Ultimately it converts a
  lazy-seq of rows inside a dataset, into a lazy-seq of RDF
  Statements.

  The function body should be composed of any number of forms, each of
  which should return a sequence of RDF quads.  These will then be
  concatenated together into a flattened lazy-seq of RDF statements.

  Rows are passed to the function one at a time as hash-maps, which
  can be destructured via Clojure's standard destructuring syntax.

  Additionally destructuring can be done on row-indicies (when a
  vector form is supplied) or column names (when a hash-map form is
  supplied)."

  [[row-bindings] & forms]
  {:pre [(or (symbol? row-bindings) (map? row-bindings)
             (vector? row-bindings))]}

  (let [row-sym (gensym "row")
        ds-sym (gensym "ds")]
    `(with-meta (fn [~ds-sym]
                  (let [ds-rows# (:rows ~ds-sym)
                        ds-meta# (meta ~ds-sym)]
                    (letfn [(graphify-row# [~row-sym]
                              (let ~(if (vector? row-bindings)
                                      (generate-vector-bindings ds-sym row-sym row-bindings)
                                      (splice-supplied-bindings row-sym row-bindings))
                                (->> (concat ~@forms)
                                     (map (fn ~'with-row-meta [triple#]
                                            (let [meta# {::row ~row-sym}
                                                  meta# (if ds-meta#
                                                          (assoc meta# ::dataset ds-meta#)
                                                          meta#)]
                                              (with-meta triple# meta#)))))))]

                      (mapcat graphify-row# ds-rows#))))
       ;; Add metadata to function definition to support
       ;; grafter.rdf.preview/graph-preview functionality.
       ;;
       ;; NOTE: We quote these form to prevent infinite recursive expansion of
       ;; the macro
       {::template (quote ~&form)
        ::defined-in-ns (quote ~(.getName *ns*))})))
