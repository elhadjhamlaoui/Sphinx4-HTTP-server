#!/usr/bin/env bash
if [ $# -eq 0 ]
  then
    mvn exec:java -Dexec.mainClass="org.jitsi.sphinx4http.server.HttpServer" -Xmx2048m

  else
    mvn exec:java -Dexec.mainClass="org.jitsi.sphinx4http.server.HttpServer" -Dexec.args="$1" -Xmx2048m 
fi





