package fr.galaisen.groomreader.fragments;

import android.app.AlertDialog;
import android.app.Dialog;
import android.app.Fragment;
import android.app.FragmentManager;
import android.app.FragmentTransaction;
import android.content.DialogInterface;
import android.content.Intent;
import android.hardware.Camera;
import android.os.Bundle;
import android.support.v7.app.AppCompatActivity;
import android.util.Base64;
import android.view.LayoutInflater;
import android.view.Menu;
import android.view.MenuInflater;
import android.view.MenuItem;
import android.view.View;
import android.view.ViewGroup;
import android.widget.Button;
import android.widget.EditText;
import android.widget.Toast;

import com.google.gson.Gson;
import com.google.zxing.BarcodeFormat;
import com.google.zxing.Result;
import fr.galaisen.groomreader.GroomApplication;
import fr.galaisen.groomreader.R;
import fr.galaisen.groomreader.model.QRTicket;
import fr.galaisen.groomreader.model.Ticket;
import fr.galaisen.groomreader.utils.GroomScannerView;
import fr.galaisen.groomreader.utils.GroomUtils;

import io.jsonwebtoken.*;

import java.security.KeyFactory;
import java.security.NoSuchAlgorithmException;
import java.security.PublicKey;
import java.security.interfaces.ECKey;
import java.security.spec.InvalidKeySpecException;
import java.security.spec.X509EncodedKeySpec;
import java.util.ArrayList;
import java.util.Date;
import java.util.List;

import me.dm7.barcodescanner.zxing.ZXingScannerView;
import org.jose4j.lang.JoseException;
import retrofit2.Call;
import retrofit2.Callback;
import retrofit2.Response;

public class ScannerFragment extends Fragment implements ZXingScannerView.ResultHandler {
    private GroomScannerView groomScannerView;

    private String cameraIDUsed;

    @Override
    public View onCreateView(LayoutInflater inflater, ViewGroup container, Bundle state) {
        groomScannerView = new GroomScannerView(getActivity());
        groomScannerView.setAutoFocus(true);

        setupFormats();
        if (!setUpBackCamera()) cameraIDUsed = "0";
        return groomScannerView;
    }

    @Override
    public void onCreate(Bundle state) {
        super.onCreate(state);
        setHasOptionsMenu(true);
    }

    @Override
    public void onCreateOptionsMenu(Menu menu, MenuInflater inflater) {
        super.onCreateOptionsMenu(menu, inflater);
    }

    @Override
    public void onResume() {
        super.onResume();
        groomScannerView.setResultHandler(this);
        ((AppCompatActivity) getActivity()).getSupportActionBar().setTitle(getString(R.string.scanner));
        groomScannerView.startCamera(Integer.valueOf(cameraIDUsed));
    }

    @Override
    public void onPause() {
        super.onPause();
        groomScannerView.stopCamera();
    }

    public void setupFormats() {
        List<BarcodeFormat> formats = new ArrayList<BarcodeFormat>();
        formats.add(BarcodeFormat.QR_CODE);

        if (groomScannerView != null) {
            groomScannerView.setFormats(formats);
        }
    }


    @Override
    public boolean onOptionsItemSelected(MenuItem item) {
        switch (item.getItemId()) {
            case R.id.turn_flash:
                if (groomScannerView.getFlash()) {
                    groomScannerView.setFlash(false);
                    if (!groomScannerView.getFlash()) item.setIcon(R.drawable.ic_action_flash);
                } else {
                    groomScannerView.setFlash(true);
                    if (groomScannerView.getFlash()) item.setIcon(R.drawable.ic_action_flash_light);
                }
                break;
            case R.id.swap_camera:
                changeCamera();
                groomScannerView.stopCamera();
                groomScannerView.startCamera(Integer.valueOf(cameraIDUsed));
                break;
            case R.id.by_id:
                groomScannerView.stopCamera();
                launchDialog();
                break;
            case R.id.by_name:
                groomScannerView.stopCamera();
                launchSearch();
                break;
            default:
                return super.onOptionsItemSelected(item);
        }
        return super.onOptionsItemSelected(item);
    }

    @Override
    public void handleResult(Result result) {
        try {
            X509EncodedKeySpec pubKeySpec = new X509EncodedKeySpec(Base64.decode(getString(R.string.public_key), Base64.DEFAULT));
            KeyFactory keyFactory = KeyFactory.getInstance("ECDSA");
            PublicKey publicKey = keyFactory.generatePublic(pubKeySpec);

            Jws<Claims> claims = Jwts.parser().setSigningKey(publicKey).parseClaimsJws(result.getText());
            Gson gson = new Gson();
            QRTicket ticket = new QRTicket();
            ticket.id = String.valueOf(claims.getBody().get("id", Integer.class));
            ticket.ln = claims.getBody().get("ln", String.class);
            ticket.fn = claims.getBody().get("fn", String.class);
            ticket.prid = String.valueOf(claims.getBody().get("prid", Integer.class));
            ticket.iat = String.valueOf(claims.getBody().get("iat", Date.class).getTime() / 1000);
            ticket.orid = String.valueOf(claims.getBody().get("orid", Integer.class));
            launchResult(gson.toJson(ticket));
        } catch (SignatureException e) {
            launchResult("");
        } catch (NoSuchAlgorithmException e) {
            e.printStackTrace();
        } catch (InvalidKeySpecException e) {
            e.printStackTrace();
        } catch (Exception ex) {
            ex.printStackTrace();
        }
    }


    private void launchDialog() {
        AlertDialog.Builder builder = new AlertDialog.Builder(getActivity());

        builder.setPositiveButton(R.string.scanner, new DialogInterface.OnClickListener() {
            public void onClick(DialogInterface dialog, int id) {
                Dialog f = (Dialog) dialog;
                EditText ticketID = (EditText) f.findViewById(R.id.dialog_ticket_id);

                if (GroomUtils.userConnected()) {
                    GroomApplication.service.getTicket(Integer.valueOf(ticketID.getText().toString())).enqueue(new Callback<Ticket>() {
                        @Override
                        public void onResponse(Call<Ticket> call, Response<Ticket> response) {
                            if (response.code() == 200) {
                                launchManual(response.body());
                            } else {
                                if (response.code() == 404) {
                                    Toast.makeText(GroomApplication.getContext(), getString(R.string.not_found), Toast.LENGTH_SHORT).show();
                                } else {
                                    Toast.makeText(GroomApplication.getContext(), getString(R.string.server_error), Toast.LENGTH_SHORT).show();
                                }
                            }
                        }

                        @Override
                        public void onFailure(Call<Ticket> call, Throwable t) {
                            groomScannerView.startCamera(Integer.valueOf(cameraIDUsed));
                            Toast.makeText(GroomApplication.getContext(), getString(R.string.service_failure), Toast.LENGTH_SHORT).show();
                        }
                    });
                } else {
                    groomScannerView.startCamera(Integer.valueOf(cameraIDUsed));
                    Toast.makeText(GroomApplication.getContext(), getString(R.string.notconnected), Toast.LENGTH_SHORT).show();
                }

            }
        });
        builder.setNegativeButton(R.string.cancel, new DialogInterface.OnClickListener() {
            public void onClick(DialogInterface dialog, int id) {
                groomScannerView.startCamera(Integer.valueOf(cameraIDUsed));
                dialog.cancel();
            }
        });

        LayoutInflater inflater = getActivity().getLayoutInflater();
        builder.setView(inflater.inflate(R.layout.dialog_scan, null));

        AlertDialog dialog = builder.create();
        dialog.show();

        Button negative = dialog.getButton(DialogInterface.BUTTON_NEGATIVE);
        Button positive = dialog.getButton(DialogInterface.BUTTON_POSITIVE);


        negative.setTextColor(getResources().getColor(R.color.colorAccent));
        positive.setTextColor(getResources().getColor(R.color.colorAccent));
    }

    private void launchSearch(){
        AlertDialog.Builder builder = new AlertDialog.Builder(getActivity());

        builder.setPositiveButton(R.string.search, new DialogInterface.OnClickListener() {
            public void onClick(DialogInterface dialog, int id) {
                if(GroomUtils.userConnected()){
                    Dialog f = (Dialog) dialog;
                    EditText lastname = (EditText) f.findViewById(R.id.dialog_lastname);

                    SearchFragment fragment = SearchFragment.newInstance(lastname.getText().toString());
                    FragmentManager manager = getFragmentManager();
                    FragmentTransaction transaction = manager.beginTransaction();
                    transaction.replace(R.id.scanner_container, fragment);
                    transaction.addToBackStack(null);
                    transaction.commit();
                } else {
                    Toast.makeText(GroomApplication.getContext(), getString(R.string.notconnected), Toast.LENGTH_SHORT).show();
                    groomScannerView.startCamera(Integer.valueOf(cameraIDUsed));
                }

            }
        });

        builder.setNegativeButton(R.string.cancel, new DialogInterface.OnClickListener() {
            public void onClick(DialogInterface dialog, int id) {
                groomScannerView.startCamera(Integer.valueOf(cameraIDUsed));
                dialog.cancel();
            }
        });

        LayoutInflater inflater = getActivity().getLayoutInflater();
        builder.setView(inflater.inflate(R.layout.search_dialog, null));

        AlertDialog dialog = builder.create();
        dialog.show();

        Button negative = dialog.getButton(DialogInterface.BUTTON_NEGATIVE);
        Button positive = dialog.getButton(DialogInterface.BUTTON_POSITIVE);
        negative.setTextColor(getResources().getColor(R.color.colorAccent));
        positive.setTextColor(getResources().getColor(R.color.colorAccent));
    }

    private boolean changeCamera() {
        int camBackId = Camera.CameraInfo.CAMERA_FACING_BACK;
        int camFrontId = Camera.CameraInfo.CAMERA_FACING_FRONT;

        if (Integer.valueOf(cameraIDUsed) == camBackId) {
            cameraIDUsed = String.valueOf(camFrontId);
        } else {
            cameraIDUsed = String.valueOf(camBackId);
        }

        return true;
    }

    private boolean setUpBackCamera() {
        int camBackId = Camera.CameraInfo.CAMERA_FACING_BACK;
        cameraIDUsed = String.valueOf(camBackId);
        return true;
    }

    private void launchResult(String jsonResult) {
        ResultFragment fragment = ResultFragment.newInstance(jsonResult, null, false);
        FragmentManager manager = getFragmentManager();
        FragmentTransaction transaction = manager.beginTransaction();
        transaction.replace(R.id.scanner_container, fragment);
        transaction.addToBackStack(null);
        transaction.commit();
    }

    private void launchManual(Ticket ticket) {
        ResultFragment fragment = ResultFragment.newInstance(null, ticket, true);
        FragmentManager manager = getFragmentManager();
        FragmentTransaction transaction = manager.beginTransaction();
        transaction.replace(R.id.scanner_container, fragment);
        transaction.addToBackStack(null);
        transaction.commit();
    }
}
