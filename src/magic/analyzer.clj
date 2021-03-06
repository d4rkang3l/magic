(ns magic.analyzer
  (:refer-clojure :exclude [macroexpand-1])
  (:require [clojure.tools.analyzer :as ana]
            [clojure.tools.analyzer.ast :refer [nodes prewalk]]
            [clojure.tools.analyzer.utils :refer [update-vals]]
            [clojure.tools.analyzer.passes :refer [schedule]]
            [clojure.tools.analyzer.passes
             [source-info :refer [source-info]]
             [cleanup :refer [cleanup]]
             [elide-meta :refer [elide-meta elides]]
             [warn-earmuff :refer [warn-earmuff]]
             [collect-closed-overs :refer [collect-closed-overs]]
             [add-binding-atom :refer [add-binding-atom]]
             [uniquify :refer [uniquify-locals]]]
            [clojure.tools.analyzer.env :refer [*env* with-env] :as env]
            [clojure.tools.analyzer.utils :refer [resolve-sym ctx -source-info resolve-ns obj? dissoc-env]]
            [magic.analyzer
             [intrinsics :as intrinsics]
             [util :as util]
             [literal-reinterpretation :as lr]
             [novel :as novel]
             [analyze-host-forms :as host]
             [errors :refer [error] :as errors]
             [types :refer [ast-type class-for-name maybe-class]]]))

(defn ensure-class [c form]
  (or (class-for-name c)
      (errors/error
        ::errors/missing-type
        {:type c :form form})))

(defn desugar-host-expr [form env]
  (cond
   (symbol? form)
   (let [target (maybe-class (namespace form))]
     (if (and target
              (not (resolve-ns (symbol (namespace form)) env))
              (ensure-class target form))       ;; Class/field
       (with-meta (list '. target (symbol (str "-" (symbol (name form))))) ;; transform to (. Class -field)
         (meta form))
       form))

   (seq? form)
   (let [[op & expr] form]
     (if (symbol? op)
       (let [opname (name op)
             opns   (namespace op)]
         (cond

          (.StartsWith opname ".") ; (.foo bar ..)                                    ;;; .startsWith
          (let [[target & args] expr
                ;; wrap type literals in clojure.core/identity
                ;; disambiguates between targeting the type literal or the class
                ;; cleaned up in later analysis passes
                ;; e.g. (.GetMethods DateTime) vs (DateTime/Now)
                target (if-let [target (and (not (get (:locals env) target))
                                            (class-for-name target))]
                         (with-meta (list 'clojure.core/identity target)
                           {:tag 'System.Type})                                       ;;; java.lang.Class
                         target)
                args (list* (symbol (subs opname 1)) args)]
            (with-meta (list '. target (if (= 1 (count args)) ;; we don't know if (.foo bar) is
                                         (first args) args))  ;; a method call or a field access
              (meta form)))

          (and (maybe-class opns)
               (not (resolve-ns (symbol opns) env))) ; (class/field ..)
          (let [target (maybe-class opns)
                op (symbol opname)]
            (ensure-class target form)
            (with-meta (list '. target (if (zero? (count expr))
                                         op
                                         (list* op expr)))
                       (meta form)))

          (.EndsWith opname ".") ;; (class. ..)                      ;;; .endsWith
          (with-meta (list* 'new (symbol (subs opname 0 (dec (count opname)))) expr)
            (meta form))

          :else form))
       form))

   :else form))

(defn build-ns-map []
  (into {} (mapv #(vector (ns-name %)
                          {:mappings (ns-map %)
                           :aliases  (reduce-kv (fn [a k v] (assoc a k (ns-name v)))
                                                {} (ns-aliases %))
                           :ns       (ns-name %)})
                 (all-ns))))

(defn global-env []
  (atom {:namespaces (build-ns-map)}))

(def specials
  "Set of the special forms for clojure in the CLR"
  (into ana/specials
        '#{var monitor-enter monitor-exit clojure.core/import* reify* deftype* case*}))

(defn parse-monitor-enter
  [[_ target :as form] env]
  (when-not (= 2 (count form))
    (throw (ex-info (str "Wrong number of args to monitor-enter, had: " (dec (count form)))
                    (merge {:form form}
                           (-source-info form env)))))
  {:op       :monitor-enter
   :env      env
   :form     form
   :target   (ana/analyze target (ctx env :ctx/expr))
   :children [:target]})

(defn parse-monitor-exit
  [[_ target :as form] env]
  (when-not (= 2 (count form))
    (throw (ex-info (str "Wrong number of args to monitor-exit, had: " (dec (count form)))
                    (merge {:form form}
                           (-source-info form env)))))
  {:op       :monitor-exit
   :env      env
   :form     form
   :target   (ana/analyze target (ctx env :ctx/expr))
   :children [:target]})

(defn parse
  "Extension to tools.analyzer/-parse for CLR special forms"
  [form env]
  ((case (first form)
     monitor-enter        parse-monitor-enter
     monitor-exit         parse-monitor-exit
     #_:else              ana/-parse)
   form env))

(defn empty-env
  "Returns an empty env map"
  []
  {:context    :ctx/expr
   :locals     {}
   :ns         (ns-name *ns*)})

(defn update-ns-map! []
  (swap! *env* assoc-in [:namespaces] (build-ns-map)))

(defn macroexpand-1
  "If form represents a macro form or an inlineable function,
   returns its expansion, else returns form."
  ([form] (macroexpand-1 form (empty-env)))
  ([form env]
     (env/ensure (global-env)
       (if (seq? form)
         (let [[op & args] form]
           (if (specials op)
             form
             (let [v (resolve-sym op env)
                   m (meta v)
                   local? (-> env :locals (get op))
                   macro? (and (not local?) (:macro m)) ;; locals shadow macros
                   inline-arities-f (:inline-arities m)
                   intrinsic-expr? (@intrinsics/intrinsic-forms (util/var-symbol v))
                   inline? (and (not intrinsic-expr?)
                                (not local?)
                                (or (not inline-arities-f)
                                    (inline-arities-f (count args)))
                                (:inline m))
                   t (:tag m)]
               (cond

                macro?
                (let [res (apply v form (:locals env) (rest form))] ; (m &form &env & args)
                  (update-ns-map!)
                  (if (obj? res)
                    (vary-meta res merge (meta form))
                    res))

                inline?
                (let [res (apply inline? args)]
                  (update-ns-map!)
                  (if (obj? res)
                    (vary-meta res merge
                               (and t {:tag t})
                               (meta form))
                    res))

                :else
                (desugar-host-expr form env)))))
         (desugar-host-expr form env)))))

;; patch analysis of locals to carry binding inits with them
;; good type resolution depends on it
;; defeats the 'useless passes' optimization in tools.analyzer (because the passes are useful)
(defmethod clojure.tools.analyzer/-analyze-form clojure.lang.Symbol
  [form env]
  (merge (clojure.tools.analyzer/analyze-symbol form env)
         (when-let [{:keys [init children] :as local-binding} (-> env :locals form)]
           {:init     init
            :children children})))

(def run-passes)

;; TODO where should these go?
;; TODO clean up
#_
(defn collect-nodes
  [ast kw]
  (->> ast nodes (filter #(= collect-op (:op %)))))

(defn collect-vars
  [ast]
  (if (= :fn (:op ast))
    (assoc ast :vars (->> ast nodes (filter #(= :var (:op %)))))
    ast))

(defn collect-vectors
  [ast]
  (if (= :fn (:op ast))
    (assoc ast :vectors (->> ast nodes (filter #(= :vector (:op %)))))
    ast))

(defn tag-local
  [{:keys [op] :as ast} tag]
  (if (= op :local)
    (update ast :form vary-meta merge {:tag tag})
    ast))

(defn tag-catch-locals
  {:pass-info {:walk :pre :depends #{#'uniquify-locals #'host/analyze-type} :before #{#'host/analyze-host-interop}}}
  [{:keys [op class body local] :as ast}]
  (if (= op :catch)
    (let [local-type (-> class :val)
          local-name (-> local :name)]
      (assoc ast
        :local (tag-local local local-type)
        :body (prewalk body
                       (fn [{:keys [op form name] :as ast}]
                         (if (and (= op :local)
                                  (= name local-name))
                           (tag-local ast local-type)
                           ast)))))
    ast))

(defn enforce-var-arity
  {:pass-info {:walk :pre :before #{#'uniquify-locals}}}
  [{:keys [op fn args] :as ast}]
  (if (and (= op :invoke)
           (= (:op fn) :var))
    (let [fixed-arities (util/var-fixed-arities ast)
          variadic-arity (util/var-variadic-arity ast)
          argcount (count args)]
      (if (or (fixed-arities argcount)
              (and variadic-arity
                   (>= argcount variadic-arity)))
        ast
        (errors/error ::errors/var-bad-arity ast)))
    ast))

;; TODO is this knowledge too low level for the analyzer? 
(defn increment-arg-ids
  {:pass-info {:walk :pre :before #{#'uniquify-locals}}}
  [{:keys [local] :as ast}]
  (if (= local :arg)
    (update ast :arg-id inc)
    ast))

(def default-passes
  #{#'host/analyze-byref
    #'host/analyze-type
    #'host/analyze-host-field
    #'host/analyze-constructor
    #'host/analyze-host-interop
    #'host/analyze-host-call
    #'novel/csharp-operators
    #'novel/generic-type-syntax
    #'intrinsics/analyze
    #'lr/analyze
    #'increment-arg-ids
    #'tag-catch-locals
    ; #'enforce-var-arity
    ; #'source-info
    ; #'collect-vars
    ; #'cleanup
    #'elide-meta
    #'warn-earmuff
    #'collect-vars
    ; #'collect-closed-overs
    ; #'add-binding-atom
    #'uniquify-locals
    })

(def scheduled-default-passes
  (schedule default-passes))

(defn run-passes [ast]
  (scheduled-default-passes ast))

(defn analyze
  ([form] (analyze form (empty-env)))
  ([form env] (analyze form env run-passes))
  ([form env passes-fn]
   (binding [ana/macroexpand-1 macroexpand-1
             ana/create-var    (fn [sym env]
                                 (doto (intern (:ns env) sym)
                                   (reset-meta! (meta sym))))
             ana/parse         parse
             ana/var?          var?]
     (with-env (global-env) (passes-fn (ana/analyze form env))))))

(comment 
  (analyze '(. DateTime Now))
  
  (defmacro envo [a]
    `(quote ~(keys &env)))
  
  (eval
    '(let [a 99]
      (envo 9)))
  
  (clojure.core/macroexpand-1 '(let [a 12] &env))
  (clojure.core/macroexpand-1 '(. DateTime -Now))
  
  (defn same-expansion [f]
    (= (clojure.core/macroexpand-1 f) (macroexpand-1 f)))
  
  (macroexpand-1 '(. DateTime Now))
  (pprint (analyze '(.-Now DateTime)))
  
  (same-expansion '(.Now DateTime))
  
  (:op (analyze '(. "target" bar 1)))    ;; :host-call
  (:op (analyze '(. "target" (bar 1))))  ;; :host-call
  (:op (analyze '(. "target" bar)))      ;; :host-interop
  (:op (analyze '(. "foo" -bar)))        ;; :host-field
  (:op (analyze '(foo/bar 1)))           ;; :host-call
  (:op (analyze 'foo/bar))               ;; :host-field
  (:op (analyze '(.foo "target" 1)))     ;; :host-call
  (:op (analyze '(.foo "target")))       ;; :host-interop
  (:op (analyze '(.-foo "target")))      ;; :host-field
  
  (binding [*compile-path* "."]
    (compile 'aot))
  
  (aot/floo 1 2 3)
  
  (-> (analyze '(let [d (DateTime. 12)] d))
      :body
      :ret
      :env
      :locals
      (get 'd)
      :children
      pprint
      )
  
  (pprint (ast-type (analyze '(inc 3))))
  (pprint (ast-type (analyze '(int 3))))
  (pprint (analyze 'Strin))
  (pprint (analyze 'String))
  (pprint (analyze 'DateTim/Now))
  (pprint (analyze 'DateTime/Now))
  (pprint (analyze '(.-Date DateTime/Now)))
  (pprint (analyze '(Math/Sin 90)))
  (pprint (analyze '(System.Collections.ArrayList.)))
  (pprint (analyze '(System.Collections.ArrayList. (int 12))))
  (pprint (analyze '(.Reverse (System.Collections.ArrayList. (int 45)))))
  (pprint (analyze '(.. (System.Collections.ArrayList. (int 45)) ToArray Rank)))
  (pprint (analyze '(let [a 12] a)))
  (pprint (analyze '(do 1 2 3 4)))
  (pprint (analyze '(+ 1 2)))
  (pprint (analyze '(+ 1 90.1)))
  (binding [*unchecked-math* true]
    (pprint (analyze '(+ 1 2))))
  
  (require 'clojure.test)
  (require 'magic.analyzer.tests :reload)
  (clojure.test/run-tests 'magic.analyzer.tests))

