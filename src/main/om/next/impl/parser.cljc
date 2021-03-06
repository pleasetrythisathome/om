(ns
  ^{:doc "
   Generic query expression parsing and AST manipulation.

   QUERY EXPRESSIONS

   Query expressions are a variation on Datomic Pull Syntax
   http://docs.datomic.com/pull.html more suitable for generic client/server
   state transfer. It's important to note the Om Next query expression syntax is
   *not* a strict superset of Datomic Pull.

   A query expression is composed of EDN values. The grammar for query
   expressions follows:

   QueryRoot    := EdnVector(QueryExpr*)
   QueryExpr    := EdnKeyword | IdentExpr | ParamExpr | JoinExpr | UnionExpr
   IdentExpr    := EdnVector2(Keyword, EdnValue)
   ParamExpr    := EdnList2(QueryExpr | EdnSymbol, ParamMapExpr)
   ParamMapExpr := EdnMap(Keyword, EdnValue)
   JoinExpr     := EdnMap((Keyword | IdentExpr), (QueryRoot | RecurExpr))
   RecurExpr    := '...
   UnionExpr    := EdnMap(Keyword, QueryRoot)

   Note most apis in Om Next expect a QueryRoot not a QueryExpr.

   QUERY EXPRESSION AST FORMAT

   Given a QueryExpr you can get the AST via om.next.impl.parser/expr->ast.
   The following keys can appear in the AST representation:

   {:type         (:prop | :join | :call | :root)
    :key          (EdnKeyword | EdnSymbol | IdentExpr)
    :dispatch-key (EdnKeyword | EdnSymbol)
    :query        (QueryRoot | RecurExpr)
    :params       ParamMapExpr
    :children     EdnVector(AST)}

   :query and :params may or may not appear. :type :call is only for
   mutations."}
  om.next.impl.parser)

(declare expr->ast)

(defn symbol->ast [k]
  {:dispatch-key k
   :key k})

(defn keyword->ast [k]
  {:type :prop
   :dispatch-key k
   :key k})

(defn call->ast [[f args :as call]]
  (if (= 'quote f)
    (assoc (expr->ast args) :target (or (-> call meta :target) :remote))
    (let [ast (update-in (expr->ast f) [:params] merge (or args {}))]
      (cond-> ast
        (symbol? (:dispatch-key ast)) (assoc :type :call)))))

(defn query->ast
  "Convert a query to its AST representation."
  [query]
  {:type :root
   :children (into [] (map expr->ast) query)})

(defn join->ast [join]
  (let [[k v] (first join)
        ast   (expr->ast k)]
    (merge ast
      {:type :join :query v}
      (when-not (= '... v)
        {:children (into [] (map expr->ast) v)}))))

(defn ref->ast [[k id :as ref]]
  {:type :prop
   :dispatch-key k
   :key ref})

(defn expr->ast
  "Given a query expression convert it into an AST."
  [x]
  (cond
    (symbol? x)  (symbol->ast x)
    (keyword? x) (keyword->ast x)
    (map? x)     (join->ast x)
    (vector? x)  (ref->ast x)
    (seq? x)     (call->ast x)
    :else        (throw
                   (ex-info (str "Invalid expression " x)
                     {:type :error/invalid-expression}))))

(defn wrap-expr [root? expr]
  (if root?
    (with-meta
      (cond-> expr (keyword? expr) list)
      {:query-root true})
    expr))

(defn ast->expr
  "Given a query expression AST convert it back into a query expression."
  ([ast]
    (ast->expr ast false))
  ([{:keys [type] :as ast} unparse-children?]
   (if (= :root type)
     (into [] (map ast->expr) (:children ast))
     (let [{:keys [key query query-root params]} ast]
       (wrap-expr query-root
         (if-not (nil? params)
           (if-not (empty? params)
             (list (ast->expr (dissoc ast :params)) params)
             (list (ast->expr (dissoc ast :params))))
           (if-not (nil? query)
             (if (true? unparse-children?)
               {key (ast->expr (:children ast) unparse-children?)}
               {key query})
             key)))))))

(defn path-meta [x path]
  (let [x' (cond->> x
             (map? x) (into {} (map (fn [[k v]] [k (path-meta v (conj path k))])))
             (vector? x) (into [] (map-indexed #(path-meta %2 (conj path %1)))))]
    (cond-> x'
      #?(:clj  (instance? clojure.lang.IObj x')
         :cljs (satisfies? IWithMeta x'))
      (vary-meta assoc :om-path path))))

(defn rethrow? [x]
  (and (instance? #?(:clj clojure.lang.ExceptionInfo :cljs ExceptionInfo) x)
       (= :om.next/abort (-> x ex-data :type))))

(defn parser
  "Given a :read and/or :mutate function return a parser. Refer to om.next/parser
   for top level documentation."
  [{:keys [read mutate] :as config}]
  (fn self
    ([env query] (self env query nil))
    ([env query target]
     (let [elide-paths? (boolean (:elide-paths config))
           {:keys [path] :as env}
           (cond-> (assoc env :parser self :target target :query-root :om.next/root)
             (not (contains? env :path)) (assoc :path []))]
       (letfn [(step [ret expr]
                 (let [{query' :query :keys [key dispatch-key params] :as ast} (expr->ast expr)
                       env   (cond-> (merge env {:ast ast :query query'})
                               (= '... query') (assoc :query query)
                               (vector? key)   (assoc :query-root key))
                       type  (:type ast)
                       call? (= :call type)
                       res   (when (nil? (:target ast))
                               (case type
                                 :call
                                 (do
                                   (assert mutate "Parse mutation attempted but no :mutate function supplied")
                                   (mutate env dispatch-key params))
                                 (:prop :join)
                                 (do
                                   (assert read "Parse read attempted but no :read function supplied")
                                   (read env dispatch-key params))))]
                   (if-not (nil? target)
                     (let [ast' (get res target)]
                       (cond-> ret
                         (true? ast') (conj expr)
                         (map? ast') (conj (ast->expr ast'))))
                     (if-not (or call? (nil? (:target ast)) (contains? res :value))
                       ret
                       (let [error   (atom nil)
                             mut-ret (atom nil)]
                         (when (and call? (not (nil? (:action res))))
                           (try
                             (reset! mut-ret ((:action res)))
                             (catch #?(:clj Throwable :cljs :default) e
                               (if (rethrow? e)
                                 (throw e)
                                 (reset! error e)))))
                         (let [value (:value res)]
                           (cond-> ret
                             (not (nil? value)) (assoc key value)
                             @mut-ret (assoc-in [key :result] @mut-ret)
                             @error (assoc key {:om.next/error @error}))))))))]
         (cond-> (reduce step (if (nil? target) {} []) query)
           (not (or (not (nil? target)) elide-paths?)) (path-meta path)))))))

(defn dispatch [_ k _] k)
