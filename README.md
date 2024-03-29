# Elector
Leadership election for Spring services based on service discovery provided by [Spring Cloud Kubernetes](https://spring.io/projects/spring-cloud-kubernetes).

The project allows self-sustained instance management for services deployed to Kubernetes.  
The algorithm works by applying ordered numbers from a pool of defined size to every new instance of service.  
For the pool size equal to one, it behaves as simple leader election with one master instance and the rest marked as minions (spare instances).  
Bigger pool sizes can be used for services that are dependent on some external factors like limited pool of connections any given service can use.  
Any instance with an order number assigned becomes active and based on the number may be able to determine the portion of configuration it will use.

## Spring Simple Service Discovery with elector-demo-simple application

```shell
cd elector-demo-simple
mvn spring-boot:build-image -Dspring-boot.build-image.imageName=elector-demo-simple
docker-compose up -d
docker-compose logs
docker-compose down
```

## Kubernetes Service Discovery with elector-demo-kubernetes application

### Prepare Kubernetes

```shell
cd elector-demo-kubernetes
eval $(minikube -p minikube docker-env)
kubectl apply -f ./update-default-service-account.yml -n default
/bin/sh ./kubernetes-deploy.sh
```

## Consul Service Discovery with elector-demo-consul application

```shell
cd elector-demo-consul
docker build -t elector-demo-consul .
docker-compose up -d
docker-compose logs
docker-compose down
```

## Acknowledgments

This library was inspired by [democracy project](https://www.npmjs.com/package/democracy).
