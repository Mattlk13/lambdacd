(ns lambdacd.smoketest.smoke-test
  (:require [lambdacd.smoketest.steps :as steps]
            [org.httpkit.server :as http-kit]
            [org.httpkit.client :as http]
            [clojure.test :refer :all]
            [clojure.data.json :as json]
            [lambdacd.smoketest.pipeline :as pipeline]
            [lambdacd.util.internal.bash :as bash-util]
            [lambdacd.core :as core]
            [lambdacd.runners :as runners]
            [lambdacd.ui.core :as ui-core]))

(def url-base "http://localhost:3000")
(defn- test-server [handler]
  (http-kit/run-server handler {:port 3000}))

(defn- server-status []
  (:status (deref (http/get (str url-base "/api/builds/1/")))))

(defn- nth-build [n]
  (let [response (deref (http/get (str url-base "/api/builds/" n "/")))
        data          (:body response)]
    (if (= 200 (:status response))
      (json/read-str data)
      (throw (Exception. (str "Unexpected status code: " (:status response) response))))))

(defn- first-build []
  (nth-build 1))
(defn- second-build []
  (nth-build 2))


(defn- manual-trigger []
  (get (first (first-build)) "result"))

(defn- manual-trigger-state []
  (get (manual-trigger)  "status"))

(defn- manual-trigger-id []
  (get (manual-trigger) "trigger-id"))

(defn- in-parallel-step-result [build]
  (get (nth build 3) "result"))

(defn- in-parallel-status [build]
  (get (in-parallel-step-result build) "status"))

(defn- post-empty-json-to [url]
  (:status (deref (http/post
                    url
                    {:body "{}" :headers { "Content-Type" "application/json"}}))))

(defn- trigger-manual-trigger []
  (post-empty-json-to (str (str url-base "/api/dynamic/") (manual-trigger-id))))


(defn- retrigger-increment-counter-by-three []
  (post-empty-json-to (str url-base "/api/builds/1/4/retrigger")))

(defn wait-a-bit []
  (Thread/sleep 2000)) ; TODO: make more robust, wait for something specific

(defmacro with-server [server & body]
  `(let [server# ~server]
     (try
       ~@body
       (finally (server#)))))

(defn- create-test-repo-at [dir]
  (bash-util/bash dir
             "git init"
             "touch foo"
             "git add -A"
             "git commit -m \"some message\""))

(defn- commit [dir]
  (bash-util/bash dir
             "echo \"world\" > foo"
             "git add -A"
             "git commit -m \"some message\""))

(deftest ^:smoke smoke-test
  (testing "that we can run a pipeline"
    (create-test-repo-at steps/some-repo-location)
    (let [pipeline (core/assemble-pipeline pipeline/pipeline-def pipeline/config)]
      (runners/start-one-run-after-another pipeline)
      (with-server (test-server (ui-core/ui-for pipeline))
        (is (= 200 (server-status)))
        (is (= "waiting" (manual-trigger-state)))
        (is (= 200 (trigger-manual-trigger)))
        (wait-a-bit)
        (is (= "success" (manual-trigger-state)))
        (commit steps/some-repo-location)
        (wait-a-bit)
        (is (= 5 @steps/some-counter))
        (is (= "world\n" @steps/some-value-read-from-git-repo))
        (is (= "hello world\n" @steps/the-global-value))
        (is (= "success" (in-parallel-status (first-build))))
        (is (= 200 (retrigger-increment-counter-by-three)))
        (wait-a-bit)
        (is (= "success" (in-parallel-status (second-build))))
        (is (= 10 @steps/some-counter))))))
