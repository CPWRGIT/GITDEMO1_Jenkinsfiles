${JOBCARD}
/*JOBPARM S=*
//*
//*** THE JOB CARD MUST INCLUDE A NOTIFY STATEMENT SUCH
//*** AS NOTIFY=&SYSUID and also a REGION=0M parameter
//*
//********************************************************************
//* Execute Target Runner
//********************************************************************
//RUNNER EXEC PGM=TTTRUNNR
//*
//* This is a dynamic JCL skeleton for submitting Total Test that can
//* be reused for all test cases.
//* The first DD statement is automatically setup at execution time
//* with the ECC SLCXLOAD dataset that contains the Topaz for Total
//* Test TTTRUNNR program.
//* The second DD statement is automatically setup with the loadlib(s)
//* containing the programs to run during the test.
//* The third DD statement is only required if running the JCL
//* from Topaz for Total Test CLI with Code Coverage support.
//* If testing an LE application it should be changed to the
//* loadlib containing the COBOL runtime(CEE.SCEERUN), otherwise
//* it can be removed.
//*
//* The skeleton can be modified as needed
//*
//STEPLIB DD DISP=SHR,DSN=${lpar_tttrunner_lib}
//        DD DISP=SHR,DSN=${loadlibraries}
//*
//SLSF001  DD DISP=SHR,DSN=${loadlibraries}
//XPSL0001 DD DISP=SHR,DSN=${loadlibraries}
//* The following lines will initialize storage to zeroes to avoid
//* uninitialized storage assertion miscompares.
//CEEOPTS  DD  *
STORAGE(00,00,00)
/*
//TRPARM DD *
*
*        Optionally set your custom exit program here:
*
EXIT(NONE)
*
REPEAT(${runtime.repeat}),STUBS(${runtime.usestubs}),
DEBUG(${runtime.usedebug})
/*
//BININP DD DSN=${runtime.bininp},DISP=OLD
//BINREF DD DSN=${runtime.binref},DISP=OLD
//BINRES DD DSN=${runtime.binres},DISP=OLD
//*
//*      Optionally add your custom DD statements
//*DD1 DD DSN=HLQ.CUSTOM.TEST.LOAD,DISP=SHR
//SYSPRINT DD SYSOUT=*
//*
