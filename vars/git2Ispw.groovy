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

        stage ('Checkout') {

            dir('./') {
                deleteDir()
            }

            checkout scm

            javaCodePipeline()

        }

        if(BUILD_NUMBER == "1") {

            mfCodePipeline(execParms)
        
        }
        else{

            stash name: 'workspace', includes: '**', useDefaultExcludes: false

            parallel(

                mfCode: {
                    node {
                        mfCodePipeline(execParms)
                    }
                },
                javaCode: {
                    node {
                        javaCodePipeline()
                    }
                },
                failFast: true

            )
        }
    }
}