
/*
 * String utility function to check whether string is empty or whitespace only
 * or null or undefined
 *
 */
function isEmpty(str) {

  // Remove extra spaces
  str = str.replace(/\s+/g, ' ');

  switch (str) {
  case "":
  case " ":
  case null:
  case false:
  case typeof this == "undefined":
  case (/^\s*$/).test(str):
    return true;
  default:
    return false;
  }
}

/*
 * Utility function to check whether value is -1,
 * return true if -1 else false
 *
 */
function isNotApplicable(value) {

  if (!isNaN(value)) {
    // if number, convert to string
    value = value.toString();
  } else {
    // Remove extra spaces
    value = value.replace(/\s+/g, ' ');
  }

  switch (value) {
  case "-1":
  case "-1.0":
  case "-1.00":
    return true;
  default:
    return false;
  }
}

/*
 * Utility function to apply Not Applicable constraint on value,
 * returns "NA" if isNotApplicable(value) returns true
 * else value itself
 *
 */
function applyNotApplicableCheck(value){
  if (isNotApplicable(value)) {
    return "NA";
  } else {
    return value;
  }
}

/*
 * Utility function to convert given value in Bytes to KB or MB or GB or TB
 *
 */
function convertSizeToHumanReadable(value) {
  // UNITS VALUES IN BYTES
  var ONE_KB = 1024;
  var ONE_MB = 1024 * 1024;
  var ONE_GB = 1024 * 1024 * 1024;
  var ONE_TB = 1024 * 1024 * 1024 * 1024;
  var ONE_PB = 1024 * 1024 * 1024 * 1024 * 1024;

  var convertedValue = new Array();
  var newValue = value;
  var newUnit = "B";

  if (value >= ONE_PB) {
      // Convert to PBs
      newValue = (value / ONE_PB);
      newUnit = "PB";
  } else if (value >= ONE_TB) {
    // Convert to TBs
    newValue = (value / ONE_TB);
    newUnit = "TB";
  } else if(value >= ONE_GB) {
    // Convert to GBs
    newValue = (value / ONE_GB);
    newUnit = "GB";
  } else if(value >= ONE_MB) {
    // Convert to MBs
    newValue = (value / ONE_MB);
    newUnit = "MB";
  } else if(value >= ONE_KB) {
    // Convert to KBs
    newValue = (value / ONE_KB);
    newUnit = "KB";
  }

  // converted value
  convertedValue.push(newValue.toFixed(2));
  // B or KB or MB or GB or TB or PB
  convertedValue.push(newUnit);

  return convertedValue;
}

/*
* Utility function to convert milliseconds value in human readable
* form Eg "2 days 14 hrs 2 mins"
*/
function formatDurationVerbose(ms) {

  function stringify(num, unit) {
    if (num <= 0) {
      return "";
    } else if (num == 1) {
      return  num + " "+ unit;
    } else {
      return num + " "+ unit+'s';
    }
  }

  var second = 1000;
  var minute = 60 * second;
  var hour = 60 * minute;
  var day = 24 * hour;
  var week = 7 * day;
  var year = 365 * day;

  var msString = "";
  if (ms >= second && ms % second == 0) {
    msString = "";
  } else {
    msString = (ms % second) + " ms";
  }

  var secString = stringify(parseInt((ms % minute) / second), "sec");
  var minString = stringify(parseInt((ms % hour) / minute), "min");
  var hrString = stringify(parseInt((ms % day) / hour), "hr");
  var dayString = stringify(parseInt((ms % week) / day), "day");
  var wkString = stringify(parseInt((ms % year) / week), "wk");
  var yrString = stringify(parseInt(ms / year), "yr");

  var finalString = msString;

  if(ms >= second ) {
    finalString = secString + " " + finalString;
  }

  if(ms >= minute ) {
    finalString = minString + " " + secString;
  }

  if(ms >= hour ) {
    finalString = hrString + " " + minString;
  }

  if(ms >= day ) {
    finalString = dayString + " " + hrString + " " + minString;
  }

  if(ms >= week ) {
    finalString = wkString + " " + dayString + " " + hrString;
  }

  if(ms >= year ) {
    finalString = yrString  + " " + wkString + " " + dayString;
  }

  return finalString;

}

/*
 * Utility function to format given long date value to human readable string representation.
 *
 * Eg. NOV 26, 2019 18:45:30
 */
function formatDate(dateMS) {
  var months = ['JAN', 'FEB', 'MAR', 'APR', 'MAY', 'JUN' , 'JUL', 'AUG', 'SEP', 'OCT', 'NOV', 'DEC'];
  var dt = new Date(dateMS);

  var dd = dt.getDate();
  if ( dd < 10 ) { dd = '0' + dd; }

  var hh = dt.getHours();
  if ( hh < 10 ) { hh = '0' + hh; }

  var mm = dt.getMinutes();
  if ( mm < 10 ) { mm = '0' + mm; }

  var ss = dt.getSeconds();
  if ( ss < 10 ) { ss = '0' + ss; }

  var dateStr = months[dt.getMonth()] + ' ' + dd + ', ' + dt.getFullYear()
              + ' ' + hh + ':' + mm + ':' + ss;
  return dateStr;

}

/*
 * Utility function to calculate duration from given long date value and convert that duration
 * to human readable string representation.
 *
 * Eg. 2 Days 10 Hrs 12 Mins 25 Secs
 */
function getDurationInReadableForm(startDateTimeMS) {

  var start_date = new Date(startDateTimeMS);
  var now_date = new Date();

  var seconds = Math.floor((now_date - start_date) / 1000);
  var minutes = Math.floor(seconds / 60);
  var hours = Math.floor(minutes / 60);
  var days = Math.floor(hours / 24);

  hours = hours - (days * 24);
  minutes = minutes - (days * 24 * 60) - (hours * 60);
  seconds = seconds - (days * 24 * 60 * 60) - (hours * 60 * 60) - (minutes * 60);

  var durationStr = "";
  if (days > 0) {
    if (days < 2) {
      durationStr += days + ' Day ';
    } else {
      durationStr += days + ' Days ';
    }
  }
  if (hours > 0) {
    if (hours < 2) {
      durationStr += hours + ' Hr ';
    } else {
      durationStr += hours + ' Hrs ';
    }
  }
  if (minutes > 0) {
    if (minutes > 0 && minutes < 2) {
      durationStr += minutes + ' Min ';
    } else {
      durationStr += minutes + ' Mins ';
    }
  }
  durationStr += seconds + ' Secs';

  return durationStr;
}

/*
 * An event handler function to handle error events occurred in AJAX request.
 *
 */
var ajaxRequestErrorHandler = function (jqXHR, status, error) {

  var displayMessage = "Could Not Fetch Statistics. <br>Reason: ";
  if (jqXHR.status == 401) {
    displayMessage += "Unauthorized Access.";
  } else if (jqXHR.status == 404) {
    displayMessage += "Server Not Found.";
  } else if (jqXHR.status == 408) {
    displayMessage += "Request Timeout.";
  } else if (jqXHR.status == 500) {
    displayMessage += "Internal Server Error.";
  } else if (jqXHR.status == 503) {
    displayMessage += "Service Unavailable.";
  }

  if (status === "timeout") {
    displayMessage += "Request Timeout.";
  } else if (status === "error") {
    displayMessage += "Error Occurred.";
  } else if (status === "abort") {
    displayMessage += "Request Aborted.";
  } else if (status === "parsererror") {
    displayMessage += "Parser Error.";
  } else {
    displayMessage += status + " : "+error;;
  }

  displayMessage += "<br>Please check lead logs to know more.";

  $("#AutoUpdateErrorMsg").html(displayMessage).show();
}

/**
 * DataTable plugin for sorting file/data size in form of <digits><unit>.
 * It is common practice to append size units as a post fix (such as B, KB,
 * MB or GB) to a numeric string in order to easily denote the order of
 * magnitude of the file/data size. This plugin sorts such values correctly
 * keeping by considering of their magnitudes (eg 12MB, 6KB, etc).
 *
 *  Usage: Provide configuration in columnDefs, a 'file-size' as type and
           targeted column index as target.
 *
 *    $('#example').DataTable( {
 *       columnDefs: [
 *         { type: 'file-size', targets: 0 }
 *       ]
 *    } );
 */
jQuery.fn.dataTable.ext.type.order['file-size-pre'] = function ( data ) {
    var matches = data.match( /^(\d+(?:\.\d+)?)\s*([a-z]+)/i );
    var multipliers = {
        b:  1,
        bytes: 1,
        kb: 1000,
        kib: 1024,
        mb: 1000000,
        mib: 1048576,
        gb: 1000000000,
        gib: 1073741824,
        tb: 1000000000000,
        tib: 1099511627776,
        pb: 1000000000000000,
        pib: 1125899906842624
    };

    if (matches) {
        var multiplier = multipliers[matches[2].toLowerCase()];
        return parseFloat( matches[1] ) * multiplier;
    } else {
        return -1;
    };
};
