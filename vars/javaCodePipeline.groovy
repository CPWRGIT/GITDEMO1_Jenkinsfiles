#!/usr/bin/env groovy
import hudson.model.*
import hudson.EnvVars
import java.net.URL
import groovy.xml.*

def call(){

    unstash name: 'workspace'

    def javaRootFolder = './InsuranceSpringServer'

    stage('Unit Tests') {

        echo "[Info] - Running Unit Tests."

        // dir(javaRootFolder) {

        //     def stdOut = bat(
        //         script:         'gradlew test',
        //         returnStdout:   true
        //     )

        //     echo stdOut

        // }
    }

    stage('Build') {

        echo "[Info] - Building Java Code."

        // dir(javaRootFolder) {

        //     def stdOut = bat(
        //         script:         'gradlew build',
        //         returnStdout:   true
        //     )

        //     echo stdOut

        // }
    }
}
