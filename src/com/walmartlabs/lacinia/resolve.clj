; Copyright (c) 2017-present Walmart, Inc.
;
; Licensed under the Apache License, Version 2.0 (the "License")
; you may not use this file except in compliance with the License.
; You may obtain a copy of the License at
;
;     http://www.apache.org/licenses/LICENSE-2.0
;
; Unless required by applicable law or agreed to in writing, software
; distributed under the License is distributed on an "AS IS" BASIS,
; WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
; See the License for the specific language governing permissions and
; limitations under the License.

(ns com.walmartlabs.lacinia.resolve
  "Complex results for field resolver functions.

  Resolver functions may return a value directly, or return a value in an immediate
  or asynchronous [[ResolverResult]].  The resolved value may be wrapped in a
  a modifier:

  * [[with-error]]
  * [[with-warning]]
  * [[with-context]]
  * [[with-extensions]]

  The modifiers exist to resolve a value _and_ to perform a side effect, such as
  adding an error to the execution result.

  A value or wrapped value may be returned asynchronously using a [[ResolverResultPromise]].

  The [[FieldResolver]] protocol allows a Clojure record to act as a field resolver function."
  (:require
    [com.walmartlabs.lacinia.select-utils :refer [is-wrapped-value? wrap-value assert-error-map]])
  (:import
    (java.util.concurrent Executor)
    (clojure.lang APersistentMap)))

(def ^{:dynamic true
       :added "0.20.0"} ^Executor *callback-executor*
  "If non-nil, then specifies a java.util.concurrent.Executor (typically, a thread pool of some form) used to invoke callbacks
  when ResolveResultPromises are delivered."
  nil)

(def ^:private ^:dynamic *in-callback-thread* false)

(defprotocol ^{:added "0.24.0"} FieldResolver
  "Allows a Clojure record to operate as a field resolver."
  (resolve-value [this context args value]
    "The analog of a field resolver function, this method is passed the instance, and the standard
    context, field arguments, and container value, and returns a resolved value."))

(defn ^:private wrap-error-maps
  [value behavior error]
  (cond
    ;; Old behavior --- supported but not documented
    (sequential? error)
    (reduce #(wrap-value %1 behavior (assert-error-map %2)) value (reverse error))

    (some? error)
    (wrap-value value behavior (assert-error-map error))

    :else
    value))

(defn with-error
  "Wraps a value, modifying it to include an error map.

  The provided error map will be enhanced with a :location key,
  identifying where field occurs within the query document, and a :path key,
  identifying the sequence of fields (or aliases) and list indexes within the
  :data key of the result map.

  Any additional keys in the error map beyond :message (which must be present,
  and must be a string) will be added to an embedded :extensions map."
  {:added "0.19.0"}
  [value error]
  (wrap-error-maps value :error error))

(defn with-context
  "Wraps a value so that when nested fields (at any depth) are executed, the provided values will be in the context.

   The provided context-map is merged onto the application context."
  {:added "0.19.0"}
  [value context-map]
  (wrap-value value :context context-map))

(defprotocol ResolverResult
  "A special type returned from a field resolver that can contain a resolved value.
  A ResolverResult encapsulates the difference between when Lacinia invokes a field resolver function,
  and when the value computed by that field resolver is ready.

  In most cases, a field resolver returns a simple value, which is wrapped by [[resolve-as]] into
  a ResolverResult.

  More sophisticated field resolvers can return a ResolverResultPromise (via [[resolve-promise]]),
  and deliver the result's value asynchronously via [[deliver!]]."

  (on-deliver! [this callback]
    "Provides a callback that is invoked immediately after the ResolverResult is realized.
    The callback is passed the ResolverResult's value.

    `on-deliver!` should only be invoked once.
    It returns `this`.

    On a simple ResolverResult (not a ResolverResultPromise), the callback is invoked
    immediately.

    For a [[ResolverResultPromise]], the callback may be invoked on another thread.
    Per-thread bindings in place when `on-deliver!` is invoked will be restored prior
    to invoking the callback.

    The callback is invoked for side-effects; its result is ignored."))

(defprotocol ResolverResultPromise
  "A specialization of ResolverResult that supports asynchronous delivery of the resolved value and errors."

  (deliver!
    [this value]
    [this value error]
    "Invoked to realize the ResolverResult, triggering the callback to receive the value.

    The callback is invoked in the current thread, unless [[*thread-pool*]] is non-nil, in which case
    the callback is invoked in a pooled thread.

    The two arguments version is simply a convenience around the [[with-error]] modifier.

    Returns `this`."))

(defrecord ^:private ResolverResultImpl [resolved-value]

  ResolverResult

  (on-deliver! [this callback]
    (callback resolved-value)
    this))

(defn resolve-as
  "Invoked by field resolvers to wrap a simple return value as a ResolverResult.

  The two-arguments version is a convenience around using [[with-error]].

  This is an immediately realized ResolverResult.

  Use [[resolve-promise]] and [[deliver!]] for an asynchronous result.

  When [[on-deliver!]] is invoked, the provided callback is immediately invoked (in the same thread)."
  ([resolved-value]
   (->ResolverResultImpl resolved-value))
  ([resolved-value resolver-error]
   (->ResolverResultImpl (with-error resolved-value resolver-error))))

(def ^:private *promise-id-allocator (atom 0))

(defrecord ^:private ResolverResultPromiseImpl [*state promise-id dynamic-bindings]

  ResolverResult
  (on-deliver! [this callback]
    (loop []
      (let [state @*state]
        (cond
          (contains? state :resolved-value)
          (callback (:resolved-value state))

          (contains? state :callback)
          (throw (IllegalStateException. "ResolverResultPromise callback may only be set once."))

          (compare-and-set! *state state (assoc state :callback callback))
          nil

          :else
          (recur))))

    this)

  ResolverResultPromise

  (deliver! [this resolved-value]
    (loop []
      (let [state @*state]
        (when (contains? state :resolved-value)
          (throw (IllegalStateException. "May only realize a ResolverResultPromise once.")))

        (if (compare-and-set! *state state (assoc state :resolved-value resolved-value))
          (when-let [callback (:callback state)]
            (let [^Executor executor *callback-executor*]
              (if (or (nil? executor)
                    *in-callback-thread*)
                (callback resolved-value)
                (.execute executor #(with-bindings (assoc dynamic-bindings #'*in-callback-thread* true)
                                      (callback resolved-value))))))
          (recur))))

    this)

  (deliver! [this resolved-value error]
    (deliver! this (with-error resolved-value error)))

  Object

  (toString [_]
    (str "ResolverResultPromise[" promise-id

      (when (contains? @*state :callback)
        ", callback")

      (when (contains? @*state :resolved-value)
        ", resolved")

      "]")))

(defn resolve-promise
  "Returns a [[ResolverResultPromise]].

    At creation, the promise will capture per-thread bindings and ensure they are conveyed to the callback,
    should the callback be invoked asynchronously (when `*callback-executor*` is bound to an Executor).

   A value must be resolved and ultimately provided via [[deliver!]]."
  []
  (let [*state (atom {})
        promise-id (swap! *promise-id-allocator inc)
        dynamic-bindings (get-thread-bindings)]
    (->ResolverResultPromiseImpl *state promise-id dynamic-bindings)))

(defn is-resolver-result?
  "Is the provided value actually a [[ResolverResult]]?"
  {:added "0.23.0"}
  [value]
  ;; The call to satisfies? can be very expensive, so avoid it if at all possible.
  ;; Ignore nil, common scalar types, and normal maps and vectors
  (and (some? value)
    (not (or (keyword? value)
           (string? value)
           (boolean? value)
           (number? value)
           ;; These are the most common return values that we know aren't
           ;; actually ResolverResults (a defrecord will implement IPersistentMap, but not
           ;; extend APersistentMap, for example).
           (instance? APersistentMap value)
           ;; Lists, vectors and lazy lists are all sequential (but not sets, those are rare)
           (sequential? value)))
    (or (instance? ResolverResultImpl value)
      (instance? ResolverResultPromiseImpl value)
      ;; And here's the rareist case (and the expensive one):
      (satisfies? ResolverResult value))))

(defn as-resolver-fn
  "Wraps a [[FieldResolver]] instance as a field resolver function.

  If the field-resolver provided is a function or a Var, it is returned unchanged.

  Anything other value will cause an exception to be thrown."
  {:added "0.24.0"}
  [field-resolver]
  (cond
    (fn? field-resolver)
    field-resolver

    (var? field-resolver)
    field-resolver

    (satisfies? FieldResolver field-resolver)
    (fn [context args value]
      (resolve-value field-resolver context args value))

    :else
    (throw (ex-info "Not a field resolver function or FieldResolver instance."
             {:field-resolver field-resolver}))))

(defn wrap-resolver-result
  "Wraps a resolver function or ([[FieldResolver]] instance), passing the result through a wrapper function.

  The wrapper function is passed four values:  the context, arguments, and value
  as passed to the resolver, then the resolved value from the
  resolver.

  `wrap-resolver-result` understands resolver functions that return either a [[ResolverResult]]
  or a bare value, as well as values wrapped with a modifier (such as [[with-error]]).

  The wrapper-fn is passed the underlying value and must return a new value.
  The new value will be re-wrapped with modifiers as necessary.

  The new value returned by the wrapper-fn may itself be a ResolverResult, and the
  value (either plain, or inside a ResolverResult) may also be modified (via [[with-error]], etc.).

  Returns a standard field resolver function, with the standard three parameters (context, args, value)."
  {:added "0.23.0"}
  [resolver wrapper-fn]
  (let [resolver-fn (as-resolver-fn resolver)]
    ^ResolverResult
    (fn [context args initial-value]
      (let [resolved-value (resolver-fn context args initial-value)
            final-result (resolve-promise)
            deliver-final-result (fn [wrapped-values new-value]
                                   (deliver! final-result
                                     (if-not (seq wrapped-values)
                                       new-value
                                       ;; Rebuild the stack of wrapped values
                                       ;; last to first
                                       (reduce #(assoc %2 :value %1)
                                         new-value
                                         wrapped-values))))
            invoke-wrapper (fn invoke-wrapper
                             ([value]
                              (invoke-wrapper nil value))
                             ([wrapped-values value]
                              ;; Wait, did someone just say "monad"?
                              (if (is-wrapped-value? value)
                                ;; Unpack the wrapped value, and push the wrapper onto the stack
                                ;; of wrapped values.
                                (recur (cons value wrapped-values)
                                  (:value value))
                                (let [new-value (wrapper-fn context args initial-value value)]
                                  (if (is-resolver-result? new-value)
                                    (on-deliver! new-value #(deliver-final-result wrapped-values %))
                                    (deliver-final-result wrapped-values new-value))))))]

        (if (is-resolver-result? resolved-value)
          (on-deliver! resolved-value invoke-wrapper)
          (invoke-wrapper resolved-value))

        final-result))))

(defn with-extensions
  "Wraps a value with an update to the extensions for the request.

  The extensions are a map, and this applies a change to that map, as with
  clojure.core/update: the function is provided with the current value of the
  extensions map and the arguments, and returns the new value of the extensions map."
  {:added "0.31.0"}
  [value f & args]
  (wrap-value value :extensions [f args]))

(defn with-warning
  "As with [[with-error]], but the error map will be added to the :warnings
  key of the root :extensions map (not to the root :errors map).  Errors should
  only be used to indicate a substantial failure, whereas warnings are more
  advisory.  It is up to the application to determine what situations call
  for an error and what call for a warning."
  {:added "0.31.0"}
  [value warning]
  (wrap-error-maps value :warning warning))
