#!/usr/bin/env bash
if [ $# -eq 0 ]
  then
    mvn exec:java -Xmx2048m -Dexec.mainClass="org.jitsi.sphinx4http.server.HttpServer" 

  else
    mvn exec:java -Xmx2048m -Dexec.mainClass="org.jitsi.sphinx4http.server.HttpServer" -Dexec.args="$1" 
fi





