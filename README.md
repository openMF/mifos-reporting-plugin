# Mifos® Reporting Plugin for Apache Fineract®

## For Users

1. Create a directory for the Mifos® reports and copy the PRPT files in it 

```bash
    mkdir pentahoReports
```

2. Export the FINERACT_PENTAHO_REPORTS_PATH variable

```bash
    export FINERACT_PENTAHO_REPORTS_PATH="$PWD/pentahoReports/"
```    

3. [Download link for Mifos® Reporting Plugin ](https://sourceforge.net/projects/mifos/files/mifos-plugins/FineractPentahoPlugin/FineractPentahoPlugin-1.10.0.zip/download)  and extract the files (java jar files are on it)

4a. Execute only for Docker® - Create a directory, copy the Mifos® Reporting Plugin and the Pentaho® libraries in it

```bash
    mkdir fineract-pentaho  && cd fineract-pentaho
```

4b. Execute only for Apache Tomcat® - Copy the Mifos® Reporting Plugin and Pentaho® libraries in $TOMCAT_HOME/webapps/fineract-provider/WEB-INF/lib/

5. Restart Docker® or Apache Tomcat®

6. Test the Mifos® reports.

## For Developers

This project is currently only tested against the very latest and greatest bleeding edge Apache Fineract® `develop` branch on Linux Ubuntu® 24.04LTS. 
Building and using it against other Apache Fineract® versions may be possible, but is not tested or documented here.

1. Download and compile

```bash
    git clone https://github.com/openMF/fineract-pentaho.git
    cd mifos-reporting-plugin && ./mvnw -Dmaven.test.skip=true clean package && cd ..
```
2. Export the Location of Mifos® reports (PRPT files) in the following variable

```bash
    export FINERACT_PENTAHO_REPORTS_PATH="$PWD/fineract-pentaho/pentahoReports/"
```    

3. Execute Apache Fineract® with the location of the Mifos® Reporting Plugin library

```bash
java -Dloader.path=$MIFOS_PENTAHO_PLUGIN_HOME/libs/ -jar $APACHE_FINERACT_HOME/fineract-provider.jar
```

4. Test the Mifos® reports execution using the following curl example or through the Mifos Web App in the Reports Menu

```bash
    curl --location --request GET 'https://localhost:8443/fineract-provider/api/v1/runreports/Expected%20Payments%20By%20Date%20-%20Formatted?tenantIdentifier=default&locale=en&dateFormat=dd%20MMMM%20yyyy&R_startDate=01%20January%202022&R_endDate=02%20January%202023&R_officeId=1&output-type=PDF&R_loanOfficerId=-1' \
--header 'Fineract-Platform-TenantId: default' \
--header 'Authorization: Basic bWlmb3M6cGFzc3dvcmQ='
```

5. The output must be a PDF with the Expected Payment By Date Formated information in it (maybe it could have blank or zeroes if it is a fresh Apache Fineract® setup)

![alt text](https://github.com/openMF/fineract-pentaho/blob/1.8/img/screenshot_pentaho_report.png?raw=true)

The API call (above) should not fail if you follow the steps as shown, and all conditions met for the version of Apache Fineract®

If the API call (above) [fails with](https://issues.apache.org/jira/browse/FINERACT-1173) 
_`"There is no ReportingProcessService registered in the ReportingProcessServiceProvider for this report type: Pentaho"`_, 
then this Pentaho® Plugin has not been correctly registered & loaded by Apache Fineract®.

Please note that the library will work using the latest Apache Fineract® development branch (30th December 2024), also make sure you got installed the type fonts required by the reports. This Mifos® Reporting Plugin will work only on Apache Tomcat® version 10+. 

See also [`PentahoReportsTest`](src/test/java/org/mifos/fineract/pentaho/PentahoReportsTest.java) and the [`test`](test) script.


## License

This code used to be part of the Mifos® codebase before it became [Apache Fineract®](https://fineract.apache.org).
During that move, the Pentaho® related code had to be removed, because Pentaho®'s license prevents code using it from being part of an Apache Software Foundation® hosted project.

The correct technical solution to resolve such conundrums is to use a plugin architecture - which is what this is.

Note that the code and report templates in this git repo itself are
[licensed to you under the Mozilla® Public License 2.0 (MPL)](https://github.com/openMF/fineract-pentaho/blob/develop/LICENSE).
This is a separate question than the license that Pentaho® itself (i.e. the JAR/s of Pentaho®) are made available under.

## Important

* Mifos® and Mifos® Reporting Plugin are not affiliated with, endorsed by, or otherwise associated with the Apache Software Foundation® (ASF) or any of its projects.
* Apache Software Foundation® is a vendor-neutral organization and it is an important part of the brand is that Apache Software Foundation® (ASF) projects are governed independently.
* Apache Fineract®, Fineract, Apache, the Apache® feather, and the Apache Fineract® project logo are either registered trademarks or trademarks of the Apache Software Foundation®.
* Mifos® and Mifos® Reporting Plugin are not affiliated with, endorsed by, or otherwise associated with Hitachi Vantara LLC or any of its projects.
* Hitachi® and Hitachi Vantara® are registered trademarks of Hitachi, Ltd. in the U.S. and other countries. Pentaho® is a registered trademark of Hitachi Vantara LLC in the U.S. and other countries.

## Contribute

If this Mifos® Reporting Plugin project is useful to you, please contribute back to it (and to Apache Fineract®) by raising Pull Requests yourself with any enhancements you make, and by helping to maintain this project by helping other users on Issues and reviewing PR from others (you will be promoted to committer on this project when you contribute).  
We recommend that you _Watch_ and _Star_ this project on GitHub® to make it easy to get notified.

## History

This is a [Mifos® Reporting Plugin for Apache Fineract®](https://github.com/apache/fineract/blob/maintenance/1.6/fineract-doc/src/docs/en/deployment.adoc). The original work is this one https://github.com/vorburger/fineract-pentaho.

See [TODO](TODO.md) for possible future follow-up enhancement work.

The Mifos® Reporting Plugin has been updated to the Pentaho® version 9.5, please use the [`Pentaho® Report Designer version 9.5`](https://mifos.jfrog.io/artifactory/libs-snapshot-local/org/pentaho/reporting/prd-ce/9.5.0.0-SNAPSHOT/prd-ce-9.5.0.0-20230108.081758-1.zip) 


