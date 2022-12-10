# Elector

## Purpose

Leadership election for Spring services based on service discovery provided by [Spring Cloud Kubernetes](https://spring.io/projects/spring-cloud-kubernetes).

The project allows self-sustained instance management for services deployed to Kubernetes.  
The algorithm works by assigning ordered numbers from a pool of defined size to every new instance starting.  
For the pool size equal to one, it behaves as simple leader election with one master instance and the rest marked as minions (spare instances).  
Bigger pool sizes can be used for services that are dependent on some external factors like limited pool of connections any given service can use.  
Any instance with an order number assigned becomes active and based on the number may be able to determine the portion of configuration it will use.

## Usage

**Maven:**
```xml
<dependency>
    <groupId>com.elector</groupId>
    <artifactId>elector-spring-cloud-starter</artifactId>
    <version>${elector.version}</version>
</dependency>
```

## Configuration

| Name                                           | Type       | Default                    | Description                                   |
|------------------------------------------------|------------|----------------------------|-----------------------------------------------|
| spring.cloud.elector.enabled                   | boolean    | True                       | Enables election process                      |
| spring.cloud.elector.service-name              | String     | ${spring.application.name} | Service name in Service Discovery             |
| spring.cloud.elector.instance-id               | String     | Random UUID or provided    | Instance ID provided by Service Discovery     |
| spring.cloud.elector.hostname                  | String     | Detected Host IP           | Host IP provided by Service Discovery         |
| spring.cloud.elector.listener-port             | int        | 12321                      | Inter-instance communication port             |
| spring.cloud.elector.heartbeat-interval-millis | int        | 1000                       | Time between heartbeat messages               |
| spring.cloud.elector.heartbeat-timeout-millis  | int        | 3000                       | Time after which peer instance becomes ABSENT |
| spring.cloud.elector.ballot-timeout-millis     | int        | 1000                       | Time of collecting ballots                    |
| spring.cloud.elector.pool-size                 | int        | 1                          | Number of service instances to activate       |
| spring.cloud.elector.ballot-type               | BallotType | QUORUM                     | The way of resolving ballot                   |


## Demo applications

### Spring Simple Service Discovery with elector-demo-simple application

```shell
cd elector-demo-simple
mvn spring-boot:build-image -Dspring-boot.build-image.imageName=elector-demo-simple
docker-compose up -d
docker-compose logs
docker-compose down
```

### Kubernetes Service Discovery with elector-demo-kubernetes application

#### Service discovery prerequisites

Implementation of elector depends on discoverability of the services. Any instance should be discoverable as soon as it is deployed to Kubernetes.
However, [DiscoveryClient for Kubernetes](https://docs.spring.io/spring-cloud-kubernetes/docs/current/reference/html/#discoveryclient-for-kubernetes), by default is set only to report instances marked by Kubernetes as 'ready'.
Fortunately, it can be changed by setting:

```properties
spring.cloud.kubernetes.discovery.include-not-ready-addresses=true
```

It is worth mentioning that 'readiness' is managed by Kubernetes probes. If you use them in your K8s configuration, make sure the above property is set for your service.

#### Prepare Kubernetes (minikube)

```shell
cd elector-demo-kubernetes
eval $(minikube -p minikube docker-env)
kubectl apply -f ./update-default-service-account.yml -n default
/bin/sh ./kubernetes-deploy.sh
```

### Consul Service Discovery with elector-demo-consul application

```shell
cd elector-demo-consul
docker build -t elector-demo-consul .
docker-compose up -d
docker-compose logs
docker-compose down
```

## Acknowledgments

This library was inspired by [democracy project](https://www.npmjs.com/package/democracy).