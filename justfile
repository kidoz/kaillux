# Build the project (admin UI + server)
build: admin-install admin-build
    ./gradlew build

# Clean and build the project (admin UI + server)
rebuild: admin-install admin-build
    ./gradlew clean build

# Run the application
run:
    ./gradlew bootRun

# Check code styling
check-style:
    ./gradlew spotlessCheck

# Apply code styling
format:
    ./gradlew spotlessApply

# Java linting
lint:
    ./gradlew checkstyleMain

# Run load tests with configurable parameters
# Usage: just load-test [clients] [timeout]
# Example: just load-test 50 120
load-test clients="100" timeout="60":
    ./gradlew test --tests "su.kidoz.kaillera.load.ServerLoadTest" \
        -Dload.tests=true \
        -Dload.clients={{clients}} \
        -Dload.timeout={{timeout}}

# Run external server load tests (requires server running on localhost:27888)
# Usage: just load-test-external [clients]
# Example: just load-test-external 20
load-test-external clients="10":
    ./gradlew test --tests "su.kidoz.kaillera.load.ExternalServerLoadTest" \
        -Dload.tests=true \
        -Dload.external=true \
        -Dkaillera.clients={{clients}}

# Run E2E protocol tests (starts embedded server)
e2e-test:
    ./gradlew test --tests "su.kidoz.kaillera.protocol.ProtocolE2ETest"

# Admin UI commands
admin-install:
    cd src/main/resources/static/admin && npm install

admin-dev:
    cd src/main/resources/static/admin && npm run dev

admin-build:
    cd src/main/resources/static/admin && npm run build

admin-typecheck:
    cd src/main/resources/static/admin && npm run typecheck

admin-lint:
    cd src/main/resources/static/admin && npm run lint

admin-check:
    cd src/main/resources/static/admin && npm run check

admin-fix:
    cd src/main/resources/static/admin && npm run check:fix

# Build Docker image
docker-build:
    docker build -t kaillux .

# Run application in Docker
docker-run:
    docker run -p 8080:8080 -p 27888:27888/udp -p 27889-27924:27889-27924/udp kaillux
