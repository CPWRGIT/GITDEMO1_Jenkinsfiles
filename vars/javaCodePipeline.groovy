#!/usr/bin/env groovy
import hudson.model.*
import hudson.EnvVars
import java.net.URL
import groovy.xml.*

String javaRootFolder = './InsuranceSpringServer'

def call(){

    stage('Test') {

        echo "[Info] - Building Java Code."

        dir(javaRootFolder) {

            def stdOut = bat(
                script:         'gradlew test',
                returnStdout:   true
            )

            echo stdOut

        }
    }

    stage('Tests') {

        echo "[Info] - Run JUnit Tests."

        dir(javaRootFolder) {

            def stdOut = bat(
                script:         'gradlew build',
                returnStdout:   true
            )

            echo stdOut

        }
    }
}
