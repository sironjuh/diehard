(ns diehard.core
  (:require [clojure.set :as set]
            [diehard.util :as u]
            [diehard.circuit-breaker :as cb]
            [diehard.rate-limiter :as rl]
            [diehard.bulkhead :as bh])
  (:import [java.util List]
           [java.time Duration]
           [java.time.temporal ChronoUnit]
           [net.jodah.failsafe Failsafe FailurePolicy Fallback RetryPolicy
            CircuitBreaker FailsafeExecutor
            ExecutionContext FailsafeException
            CircuitBreakerOpenException]
           [net.jodah.failsafe.event ExecutionAttemptedEvent
            ExecutionCompletedEvent]
           [net.jodah.failsafe.function CheckedSupplier ContextualSupplier
            CheckedFunction]))

(def ^:const ^:no-doc
  policy-allowed-keys #{:policy :circuit-breaker

                        :retry-if :retry-on :retry-when
                        :abort-if :abort-on :abort-when
                        :backoff-ms :max-retries :max-duration-ms :delay-ms
                        :jitter-factor :jitter-ms})

(def ^:const ^:no-doc listener-allowed-keys
  #{:listener
    :on-abort :on-complete :on-failed-attempt
    :on-failure :on-retry :on-retries-exceeded :on-success})

(def ^:const ^:no-doc allowed-keys
  (set/union policy-allowed-keys listener-allowed-keys #{:fallback}))

(def ^{:dynamic true
       :doc "Available in retry block. Contextual value represents time elasped since first attempt"}
  *elapsed-time-ms*)
(def ^{:dynamic true
       :doc "Available in retry block. Contextual value represents execution times"}
  *executions*)
(def ^{:dynamic true
       :doc "Available in retry block. Contextual value represents first attempt time"}
  *start-time-ms*)

(defmacro ^:no-doc with-context [ctx & body]
  `(binding [*elapsed-time-ms* (.toMillis ^Duration (.getElapsedTime ~ctx))
             *executions* (long (.getAttemptCount ~ctx))
             *start-time-ms* (.toMillis ^Duration (.getStartTime ~ctx))]
     ~@body))

(defn ^:no-doc retry-policy-from-config [policy-map]
  (if-let [policy (:policy policy-map)]

    (.copy policy)

    (let [policy (RetryPolicy.)]
      (when (contains? policy-map :abort-if)
        (.abortIf policy (u/bipredicate (:abort-if policy-map))))
      (when (contains? policy-map :abort-on)
        (.abortOn policy (u/predicate-or-value (:abort-on policy-map))))
      (when (contains? policy-map :abort-when)
        (.abortWhen policy (:abort-when policy-map)))
      (when (contains? policy-map :retry-if)
        (.handleIf policy (u/bipredicate (:retry-if policy-map))))
      (when (contains? policy-map :retry-on)
        (.handle policy (u/predicate-or-value (:retry-on policy-map))))
      (when (contains? policy-map :retry-when)
        (if (fn? (:retry-when policy-map))
          (.handleResultIf policy (u/predicate (:retry-when policy-map)))
          (.handleResult policy (:retry-when policy-map))))
      (when (contains? policy-map :backoff-ms)
        (let [backoff-config (:backoff-ms policy-map)
              [delay max-delay multiplier] backoff-config]
          (if (nil? multiplier)
            (.withBackoff policy delay max-delay ChronoUnit/MILLIS)
            (.withBackoff policy delay max-delay ChronoUnit/MILLIS multiplier))))
      (when-let [delay  (:delay-ms policy-map)]
        (.withDelay policy (Duration/ofMillis delay)))
      (when-let [duration (:max-duration-ms policy-map)]
        (.withMaxDuration policy (Duration/ofMillis duration)))
      (when-let [retries (:max-retries policy-map -1)]
        (.withMaxRetries policy retries))
      (when-let [jitter (:jitter-factor policy-map)]
        (.withJitter policy jitter))
      (when-let [jitter (:jitter-ms policy-map)]
        (.withJitter policy (Duration/ofMillis jitter)))

      ;; events
      (when-let [on-abort (:on-abort policy-map)]
        (.onAbort policy
                  (u/fn-as-consumer
                   (fn [^ExecutionCompletedEvent event]
                     (with-context event
                       (on-abort (.getResult event) (.getFailure event)))))))
      (when-let [on-failed-attempt (:on-failed-attempt policy-map)]
        (.onFailedAttempt policy
                          (u/fn-as-consumer
                           (fn [^ExecutionAttemptedEvent event]
                             (with-context event
                               (on-failed-attempt (.getLastResult event)
                                                  (.getLastFailure event)))))))
      (when-let [on-failure (:on-failure policy-map)]
        (.onFailure policy
                    (u/fn-as-consumer
                     (fn [^ExecutionCompletedEvent event]
                       (with-context event
                         (on-failure (.getResult event) (.getFailure event)))))))

      (when-let [on-retry (:on-retry policy-map)]
        (.onRetry policy
                  (u/fn-as-consumer
                   (fn [^ExecutionAttemptedEvent event]
                     (with-context event
                       (on-retry (.getLastResult event)
                                 (.getLastFailure event)))))))

      (when-let [on-retries-exceeded (:on-retries-exceeded policy-map)]
        (.onRetriesExceeded policy
                            (u/fn-as-consumer
                             (fn [^ExecutionCompletedEvent event]
                               (with-context event
                                 (on-retries-exceeded (.getResult event) (.getFailure event)))))))

      (when-let [on-success (:on-success policy-map)]
        (.onSuccess policy
                    (u/fn-as-consumer
                     (fn [^ExecutionCompletedEvent event]
                       (with-context event
                         (on-success (.getResult event)))))))

      policy)))


(defn ^:no-doc fallback [opts]
  (when-some [fb (:fallback opts)]
    (Fallback/of ^CheckedFunction
                 (u/fn-as-checked-function
                   (fn [^ExecutionAttemptedEvent exec-event]
                     (let [fb (if-not (fn? fb) (constantly fb) fb)]
                       (with-context exec-event
                         (fb (.getLastResult exec-event) (.getLastFailure exec-event)))))))))

(defmacro ^{:doc "Predefined retry policy.
#### Available options

##### Retry criteria

* `:retry-when` retry when return value is given value
* `:retry-on` retry on given exception / exceptions(vector) were thrown
* `:retry-if` specify a function `(fn [return-value
  exception-thrown])`, retry if the function returns truthy

##### Retry abortion criteria

* `:abort-when` abort retry when return value is given value
* `:abort-on` abort retry on given exception / exceptions(vector) were
  thrown
* `:abort-if` specify a function `(fn [return-value
  exception-thrown])`, abort retry if the function returns truthy
* `:max-retries` abort retry when max attempts reached
* `:max-duration-ms` abort retry when duration reached

##### Delay

* `:backoff-ms` specify a vector `[initial-delay-ms max-delay-ms
  multiplier]` to control the delay between each retry, the delay for
  **n**th retry will be `(max (* initial-delay-ms n) max-delay-ms)`
* `:delay-ms` use constant delay between each retry
* `:jitter-factor` random factor for each delay
* `:jitter-ms` random time `(-jitter-ms, jitter-ms)` adds to each delay

##### Retry Listeners

* `:on-abort` accepts a function which takes `result`, `exception` as
  arguments, called when retry aborted
* `:on-complete` accepts a function which takes `result`, `exception` as
  arguments, called when exiting `retry` block
* `:on-failed-attempt` accepts a function which takes `result`,
  `exception` as arguments, called when execution failed (matches
  retry criteria)
* `:on-failure` accepts a function which takes `result`,
  `exception` as arguments, called when exiting `retry` block with
  failure (matches retry criteria)
* `:on-success` accepts a function which takes `result` as arguments,
  called when exiting `retry` block with success (mismatches retry
  criteria)
* `:on-retry` accepts a function which takes `result`, `exception` as
  arguments, called when a retry attempted.
* `:on-retries-exceeded` accepts a function which takes `result`,
  `exception` as arguments, called when max retries or max duration have
  been exceeded.

##### Use pre-defined policy

You can put together all those retry policies in a `defretrypolicy`.
And use `:policy` option in option map.

```clojure
(diehard/defretrypolicy policy
  {:max-retries 5
   :backoff-ms [1000 10000]})

(diehard/with-retry {:policy policy}
  ;; your code here
  )
```
"}
  defretrypolicy [name opts]
  `(let [the-opts# ~opts]
     (u/verify-opt-map-keys-with-spec :retry/retry-policy-new the-opts#)
     (def ~name (retry-policy-from-config the-opts#))))

(defmacro ^{:doc "Retry policy protected block.
If the return value of or exception thrown from the code block matches
the criteria of your retry policy, the code block will be executed
again, until it mismatch the retry policy or matches the abort
criteria. The block will return the value or throw exception from
the last execution. If `:circuit-breaker` is set, it will throw
 `CircuitBreakerOpenException` when the breaker becomes open.

#### Available options

##### Retry criteria

* `:retry-when` retry when return value is given value
* `:retry-on` retry on given exception / exceptions(vector) were thrown
* `:retry-if` specify a function `(fn [return-value
  exception-thrown])`, retry if the function returns truthy

##### Retry abortion criteria

* `:abort-when` abort retry when return value is given value
* `:abort-on` abort retry on given exception / exceptions(vector) were
  thrown
* `:abort-if` specify a function `(fn [return-value
  exception-thrown])`, abort retry if the function returns truthy
* `:max-retries` abort retry when max attempts reached
* `:max-duration-ms` abort retry when duration reached

##### Delay

* `:backoff-ms` specify a vector `[initial-delay-ms max-delay-ms
  multiplier]` to control the delay between each retry, the delay for
  **n**th retry will be `(max (* initial-delay-ms n) max-delay-ms)`
* `:delay-ms` use constant delay between each retry
* `:jitter-factor` random factor for each delay
* `:jitter-ms` random time `(-jitter-ms, jitter-ms)` adds to each delay

##### Retry Listeners

* `:on-abort` accepts a function which takes `result`, `exception` as
  arguments, called when retry aborted
* `:on-failed-attempt` accepts a function which takes `result`,
  `exception` as arguments, called when execution failed (matches
  retry criteria)
* `:on-failure` accepts a function which takes `result`,
  `exception` as arguments, called when existing `retry` block with
  failure (matches retry criteria)
* `:on-success` accepts a function which takes `result` as arguments,
  called when existing `retry` block with success (mismatches retry
  criteria)
* `:on-retry` accepts a function which takes `result` and `exception` as
  arguments, called when a retry attempted.
* `:on-retries-exceeded` accepts a function which takes `result` and
  `exception` as arguments, called when retries exceeded

##### Use pre-defined policy

You can put together all those retry policies in a `defretrypolicy`.
And use `:policy` option in option map.

```clojure
(diehard/defretrypolicy policy
  {:max-retries 5
   :backoff-ms [1000 10000]})

(diehard/with-retry {:policy policy}
  ;; your code here
  )
```

##### Retry Listeners

* `:on-abort` accepts a function which takes `result`, `exception` as
  arguments, called when retry aborted
* `:on-complete` accepts a function which takes `result`, `exception` as
  arguments, called when exiting `retry` block
* `:on-failed-attempt` accepts a function which takes `result`,
  `exception` as arguments, called when execution failed (matches
  retry criteria)
* `:on-failure` accepts a function which takes `result`,
  `exception` as arguments, called when exiting `retry` block with
  failure (matches retry criteria)
* `:on-success` accepts a function which takes `result` as arguments,
  called when exiting `retry` block with success (mismatches retry
  criteria)
* `:on-retry` accepts a function which takes `result` as arguments,
  called when a retry attempted.

##### Use predefined listeners

```clojure
(diehard/deflistener listener
  {:on-retry (fn [return-value exception-thrown] (println \"retried\"))})

(diehard/with-retry {:policy policy :listener listener}
  ;; your code here
  )

```

##### Fallback

* `:fallback` fallback value or handler function when retry blocks
  exists with failure.

```clojure
;; return 5 when attempts failure
(with-retry {:fallback 5}
  ;; ...
  )

;; return fallback handler function result when failed
(with-retry {:fallback (fn [value exception]
                         ;; value: value returned from last attempt
                         ;; exp: exception thrown from last attempt
                         )}
  ;; ...
  )

```

##### Circuit breaker
* `:circuit-breaker` a circuit breaker created from `defcircuitbreaker`.
It will work together with retry policy as quit criteria.

"}
  with-retry [opt & body]
  `(let [the-opt# ~opt]
     (u/verify-opt-map-keys-with-spec :retry/retry-block the-opt#)
     (let [retry-policy# (retry-policy-from-config the-opt#)
           fallback# (fallback the-opt#)
           cb# (:circuit-breaker the-opt#)

           policies# (into-array FailurePolicy (filter some? [fallback# retry-policy# cb#]))

           failsafe# (Failsafe/with policies#)
           failsafe# (if-let [on-complete# (:on-complete the-opt#)]
                       (.onComplete failsafe#
                                    (u/fn-as-consumer
                                     (fn [^ExecutionCompletedEvent event#]
                                       (with-context event#
                                         (on-complete# (.getResult event#)
                                                       (.getFailure event#))))))
                       failsafe#)
           callable# (reify ContextualSupplier
                       (get [_ ^ExecutionContext ctx#]
                         (with-context ctx#
                           ~@body)))]
       (try
         (.get ^FailsafeExecutor failsafe# ^ContextualSupplier callable#)
         (catch CircuitBreakerOpenException e#
           (throw e#))
         (catch FailsafeException e#
           (throw (.getCause e#)))))))

(defmacro ^{:doc "Define a circuit breaker with option.
#### Available options

There options are available when creating circuit breaker in
`defcircuitbreaker`.

##### Failure criteria

All the three `fail` options share same meaning with similar option in
retry block.

* `:fail-if`
* `:fail-on`
* `:fail-when`
* `:timeout-ms` while give all you code a timeout is best practice in
  application level, circuit breaker also provides a timeout for
  marking a long running block as failure


##### Delay and threshold

* `:delay-ms` required. the delay for `:open` circuit breaker to turn
  into `:half-open`.
* `:failure-threshold`
* `:failure-threshold-ratio`
* `:success-threshold`
* `:success-threshold-ratio` All these four option is to determine at
  what condition the circuit breaker is open.

##### Listeners

* `:on-open` a function to be called when state goes `:open`
* `:on-close` a function to be called when state goes `:closed`
* `:on-half-open` a function to be called when state goes `:half-open`
"}
  defcircuitbreaker [name opts]
  `(def ~name (cb/circuit-breaker ~opts)))

(defmacro ^{:doc "Circuit breaker protected block.

```clj
(require '[diehard.core :as diehard])

(diehard/defcircuitbreaker test-cb {:failure-threshold-ratio [35 50]
                                    :delay-ms 1000})

(diehard/with-circuit-breaker test-cb
  ;; your protected code here
  )
```

In this scenario, if the circuit breaker protected code block fails 35
times in 50 executions, as defined in `:failure-threshold-ratio`, the
`test-cb` is entering into `:open` state. When circuit breaker is
open, all execution requests will be rejected immediately.

After `:delay-ms`, the circuit breaker will be `:half-open`. At the
moment, 50 execution will be allowed, to test the state to see if it's
recovered. If success, the circuit breaker is back to `:closed`
state. Otherwise, it will be `:open` again.

The block will throw `CircuitBreakerOpenException` when the circuit breaker
is open and skip execution of inner forms. Otherwise it will return the value
or throw the exception raised from inner.

You can always check circuit breaker state with
`diehard.circuitbreaker/state`.
"}
  with-circuit-breaker [cb & body]
  `(let [opts# (if-not (map? ~cb)
                 {:circuitbreaker ~cb}
                 ~cb)
         fallback# (fallback opts#)
         cb# (:circuitbreaker opts#)

         policies# (into-array FailurePolicy (filter some? [fallback# cb#]))
         failsafe# (Failsafe/with policies#)
         failsafe# (if-let [on-complete# (:on-complete opts#)]
                     (.onComplete failsafe#
                                  (u/fn-as-consumer
                                   (fn [^ExecutionCompletedEvent event#]
                                     (with-context event#
                                       (on-complete# (.getResult event#)
                                                     (.getFailure event#))))))
                     failsafe#)

         supplier# (reify CheckedSupplier
                    (get [_]
                      ~@body))]
     (try
       (.get ^FailsafeExecutor failsafe# ^CheckedSupplier supplier#)
       (catch CircuitBreakerOpenException e#
         (throw e#))
       (catch FailsafeException e#
         (throw (.getCause e#))))))

(defmacro
  ^{:doc "Create a rate limiter with options.

* `:rate` execution permits per second.
* `:max-cached-tokens` the max size of permits we can cache when idle"}
  defratelimiter [name opts]
  `(def ~name (rl/rate-limiter (u/verify-opt-map-keys-with-spec :rate-limiter/rate-limiter-new ~opts))))

(defmacro
  ^{:doc "Rate Limiter protected block. Code execution in this block is throttled
to given rate. Use `defratelimiter` to define a ratelimiter and use it as option:

```clojure
;; create a rate limiter for 100 executions for second
(defratelimiter myfl {:rate 100})

(with-rate-limiter {:ratelimiter myfl}
  ;; your task here
  )
```

By default it will wait forever until there is permits available. You can also specify a
`max-wait-ms` to wait for a given time. If there's no permits in this period, this block
will throw a Clojure `ex-info`, with `ex-data` as

```clojure

(try
  (with-rate-limiter {:ratelimiter myfl
                      :max-wait-ms 1000}
    ;; your task here
    )
  (catch Exception e
    (is (:throttled (ex-data e)))))
```

If your execution has a greater graininess, you can customize the permits for this execution
by setting `:permits` option.

```clojure
(with-rate-limiter {:ratelimiter myfl
                    :permits (size-of-the-task)}
  ;; your task here
  )
```"}
  with-rate-limiter [opts & body]
  `(let [opts# (if (satisfies? rl/IRateLimiter ~opts)
                 {:ratelimiter ~opts}
                 ~opts)
         opts# (u/verify-opt-map-keys-with-spec :rate-limiter/rate-limiter-block opts#)
         rate-limiter# (:ratelimiter opts#)
         max-wait# (:max-wait-ms opts#)
         permits# (:permits opts# 1)]
     (if (nil? max-wait#)
       (rl/acquire! rate-limiter# permits#)
       (when-not (rl/try-acquire rate-limiter# permits# max-wait#)
         (throw (ex-info "Execution throttled." {:throttled true
                                                 :rate-limiter rate-limiter#
                                                 :max-wait-ms max-wait#}))))
     ~@body))

(defmacro
  ^{:doc "Create bulkhead config from option map.
* `concurrency` the max number of concurrent executions"}
  defbulkhead [name opts]
  `(def ~name
     (bh/bulkhead (u/verify-opt-map-keys-with-spec :bulkhead/bulkhead-new ~opts))))

(defmacro
  ^{:doc "Bulkhead block. Only given number of executions is allowed to be executed in parallel.

```clojure
;; create a bulkhead that limit concurrency to 3
(defbulkhead mybh {:concurrency 3})

(with-bulkhead {:bulkhead mybh}
  ;; your task here
  )
```

By default it will wait until there is permits available for execution.

You can add `max-wait-ms` option for change this behavior. If no permits is available
when `max-wait-ms` exceeded, an `ex-info` will be thrown with `ex-data` as `{:bulkhead true :max-wait-ms wait-timeout}`

```clojure
(try
  (with-bulkhead {:bulkhead mybh
                  :max-wait-ms 1000}
    ;; your task here
    )
  (catch Exception e
    (is (::bulkhead (ex-data e)))))
```
"}
  with-bulkhead [opts & body]
  `(let [opts# (if (satisfies? bh/IBulkhead ~opts)
                 {:bulkhead ~opts}
                 ~opts)
         opts# (u/verify-opt-map-keys-with-spec :bulkhead/bulkhead-block opts#)
         {bulkhead# :bulkhead
          timeout-ms# :max-wait-ms} opts#]
     (if (nil? timeout-ms#)
       (bh/acquire! bulkhead#)
       (bh/acquire! bulkhead# timeout-ms#))
     (try
       ~@body
       (finally
         (bh/release! bulkhead#)))))
