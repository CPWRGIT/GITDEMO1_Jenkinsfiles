#!/usr/bin/env groovy
import hudson.model.*
import hudson.EnvVars
import java.net.URL
import groovy.xml.*

def call(Map execParms){

    //**********************************************************************
    // Start of Script 
    //**********************************************************************
    node {

        stage ('Checkout and initialize') {

            dir('./') {
                deleteDir()
            }

            checkout scm

            stash name: 'workspace', includes: '**', useDefaultExcludes: false
        }

        if(buildNumber == "1") {
            mfCodePipeline(execParms)
        }
        else{

            parallel(

                mfCode: {
                    mfCodePipeline(execParms)
                },
                javaCode: {
                    javaCodePipeline()
                },
                failFast: true

            )
        }
    }
}