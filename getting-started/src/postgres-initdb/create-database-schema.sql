CREATE DATABASE "case-engine-eventstore";
CREATE DATABASE "case-engine-query";
CREATE DATABASE "keycloak";

GRANT ALL PRIVILEGES ON DATABASE "case-engine-eventstore" TO postgres;
GRANT ALL PRIVILEGES ON DATABASE "case-engine-query" TO postgres;
GRANT ALL PRIVILEGES ON DATABASE "keycloak" TO postgres;
