package io.moli.android;
import android.app.*;
import android.content.*;
import android.os.Build;
import android.text.InputType;
import android.widget.*;
final class HostUi {
    static LinearLayout screen(Activity a){LinearLayout l=new LinearLayout(a);l.setOrientation(LinearLayout.VERTICAL);int p=(int)(16*a.getResources().getDisplayMetrics().density);l.setPadding(p,p,p,p);a.setContentView(l);return l;}
    static TextView text(Activity a,LinearLayout parent,int id){TextView t=new TextView(a);t.setText(id);t.setTextSize(18);parent.addView(t);return t;}
    static Button button(Activity a,LinearLayout parent,int id,Runnable action){Button b=new Button(a);b.setText(id);parent.addView(b);b.setOnClickListener(v->action.run());return b;}
    static void command(Activity a,String action){
        if(!ProtectedKey.exists(a)){
            EditText pin=new EditText(a);pin.setInputType(InputType.TYPE_CLASS_NUMBER|InputType.TYPE_NUMBER_VARIATION_PASSWORD);
            AlertDialog dialog=new AlertDialog.Builder(a).setTitle(R.string.choose_pin).setView(pin).setPositiveButton(android.R.string.ok,null).setNegativeButton(android.R.string.cancel,null).create();
            dialog.setOnShowListener(v->dialog.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener(w->{
                String value=pin.getText().toString();if(!value.matches("[0-9]{6,32}")){pin.setError(a.getString(R.string.pin_invalid));return;}
                try{ProtectedKey.create(a,value);dialog.dismiss();command(a,action);}catch(Exception failure){error(a);}
            }));dialog.show();return;
        }
        Intent intent=new Intent(a,MoliService.class);if(action!=null)intent.setAction(action);
        a.startForegroundService(intent);
        if(Build.VERSION.SDK_INT>=33)a.requestPermissions(new String[]{android.Manifest.permission.POST_NOTIFICATIONS},1);
    }
    static int errorMessage(Throwable failure){
        while(failure.getCause()!=null)failure=failure.getCause();
        String code=failure.getMessage();
        if(code!=null)switch(code) {
            case "account_key":return R.string.meross_error_key;
            case "account_local":return R.string.meross_error_local;
            case "account_uuid_different":return R.string.meross_error_identity;
            case "account_protocol":return R.string.meross_error_protocol;
            case "account_auth":case "account_denied":case "account_input":case "account_region":return R.string.meross_error_auth;
            case "account_mfa":return R.string.meross_error_mfa;
            case "account_network":case "account_clock":return R.string.meross_error_network;
            case "account_rate":return R.string.meross_error_rate;
            case "account_expired":return R.string.meross_error_expired;
        }
        int message="configuration".equals(code)?R.string.configuration_rejected:
            "conflict".equals(code)||"configuration_conflict".equals(code)?R.string.configuration_conflict:
            "start_first".equals(code)?R.string.start_first:
            "busy".equals(code)||"running".equals(code)?R.string.busy:R.string.operation_failed;
        return message;
    }
    static void error(Activity a,Throwable failure){
        if(!a.isDestroyed())new AlertDialog.Builder(a).setMessage(errorMessage(failure)).setPositiveButton(android.R.string.ok,null).show();
    }
    static void error(Activity a){if(!a.isDestroyed())new AlertDialog.Builder(a).setMessage(R.string.operation_failed).setPositiveButton(android.R.string.ok,null).show();}
    private HostUi(){}
}
