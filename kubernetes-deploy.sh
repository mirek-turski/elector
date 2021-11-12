#!/bin/bash

kubectl delete -n default deployment elector-demo
kubectl delete -n default service elector-demo
mvn clean package -DskipTests
docker rmi elector-demo:latest
docker build -t elector-demo .
kubectl apply -f elector-demo-deployment.yml
