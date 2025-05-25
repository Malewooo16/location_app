package com.example.sagaa;

import android.content.pm.PackageManager;
import android.graphics.Typeface;
import android.location.Address;
import android.location.Geocoder;
import android.location.Location;
import android.os.AsyncTask;
import android.os.Bundle;
import android.util.Log; // Import for Log
import android.view.View;
import android.widget.LinearLayout;
import android.widget.ProgressBar;
import android.widget.TextView;
import android.widget.Toast;
import android.Manifest;

import androidx.activity.EdgeToEdge;
import androidx.annotation.NonNull;
import androidx.appcompat.app.AppCompatActivity;
import androidx.core.app.ActivityCompat;
import androidx.core.graphics.Insets;
import androidx.core.view.ViewCompat;
import androidx.core.view.WindowInsetsCompat;

import com.google.android.gms.location.FusedLocationProviderClient;
import com.google.android.gms.location.LocationServices;
import com.google.android.gms.location.Priority;

import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

import java.io.IOException;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

import okhttp3.HttpUrl;
import okhttp3.OkHttpClient;
import okhttp3.Request;
import okhttp3.Response;

public class MainActivity extends AppCompatActivity {

    private static final String TAG = "MainActivity"; // For logging
    private FusedLocationProviderClient fusedLocationClient;
    private TextView textLocation;
    private LinearLayout placeListLayout;
    private ProgressBar progressBar;

    // IMPORTANT: Your API key should NEVER be hardcoded like this in a production app.
    // Use build flavors, encrypted secrets, or server-side solutions.
    private final String API_KEY = "api_key";
    private final List<String> TYPES = Arrays.asList("park", "shopping_mall", "church", "tourist_attraction");

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        EdgeToEdge.enable(this); // Keep this if using EdgeToEdge
        setContentView(R.layout.activity_main);

        ViewCompat.setOnApplyWindowInsetsListener(findViewById(R.id.main), (v, insets) -> {
            Insets systemBars = insets.getInsets(WindowInsetsCompat.Type.systemBars());
            v.setPadding(systemBars.left, systemBars.top, systemBars.right, systemBars.bottom);
            return insets;
        });

        textLocation = findViewById(R.id.textLocation);
        placeListLayout = findViewById(R.id.placeListLayout);
        progressBar = findViewById(R.id.progressBar);

        fusedLocationClient = LocationServices.getFusedLocationProviderClient(this);
        checkPermissionsAndFetchLocation();
    }

    private void checkPermissionsAndFetchLocation() {
        if (ActivityCompat.checkSelfPermission(this, Manifest.permission.ACCESS_FINE_LOCATION) != PackageManager.PERMISSION_GRANTED) {
            ActivityCompat.requestPermissions(
                    this,
                    new String[]{Manifest.permission.ACCESS_FINE_LOCATION},
                    1001
            );
        } else {
            fetchLocation();
        }
    }

    private void fetchLocation() {
        progressBar.setVisibility(View.VISIBLE);
        // This check is redundant here because it's handled in checkPermissionsAndFetchLocation
        // but it's good to have a final check before calling location services.
        if (ActivityCompat.checkSelfPermission(this, Manifest.permission.ACCESS_FINE_LOCATION) != PackageManager.PERMISSION_GRANTED &&
                ActivityCompat.checkSelfPermission(this, Manifest.permission.ACCESS_COARSE_LOCATION) != PackageManager.PERMISSION_GRANTED) {
            Log.e(TAG, "Location permissions not granted when trying to fetch location.");
            progressBar.setVisibility(View.GONE); // Hide progress bar if permissions are somehow missing here
            Toast.makeText(this, "Location permissions not granted.", Toast.LENGTH_LONG).show();
            return;
        }

        fusedLocationClient.getCurrentLocation(Priority.PRIORITY_HIGH_ACCURACY, null)
                .addOnSuccessListener(location -> {
                    if (location != null) {
                        Log.d(TAG, "Location obtained: " + location.getLatitude() + ", " + location.getLongitude());
                        new FetchPlacesTask().execute(location.getLatitude(), location.getLongitude());
                        getLocationName(location);
                    } else {
                        // Location is null, which can happen if location services aren't ready
                        Log.w(TAG, "Last known location is null.");
                        progressBar.setVisibility(View.GONE);
                        Toast.makeText(MainActivity.this, "Could not get current location. Please try again.", Toast.LENGTH_LONG).show();
                        displayEmptyState("Could not get your location. Please check settings.");
                    }
                })
                .addOnFailureListener(e -> { // <--- ADDED addOnFailureListener
                    Log.e(TAG, "Failed to get current location: " + e.getMessage(), e);
                    progressBar.setVisibility(View.GONE);
                    Toast.makeText(MainActivity.this, "Failed to get location: " + e.getLocalizedMessage(), Toast.LENGTH_LONG).show();
                    displayEmptyState("Failed to get your location. " + e.getLocalizedMessage());
                });
    }

    private void getLocationName(Location location) {
        Geocoder geocoder = new Geocoder(this, Locale.getDefault());
        try {
            List<Address> addresses = geocoder.getFromLocation(location.getLatitude(), location.getLongitude(), 1);
            if (addresses != null && !addresses.isEmpty()) {
                String locality = addresses.get(0).getLocality();
                if (locality == null || locality.isEmpty()) { // Handle cases where locality is null/empty
                    locality = addresses.get(0).getFeatureName(); // Fallback to featureName
                }
                textLocation.setText("You're near: " + locality);
            } else {
                textLocation.setText("You're near: Unknown Location");
                Log.w(TAG, "No addresses found for location: " + location.getLatitude() + "," + location.getLongitude());
            }
        } catch (IOException e) {
            Log.e(TAG, "Geocoder failed: " + e.getMessage(), e);
            e.printStackTrace();
            textLocation.setText("Location details unavailable");
            Toast.makeText(this, "Could not get location name.", Toast.LENGTH_SHORT).show();
        } catch (IllegalArgumentException e) { // Catches invalid lat/lon
            Log.e(TAG, "Invalid latitude or longitude for Geocoder: " + e.getMessage(), e);
            textLocation.setText("Location details invalid");
        }
    }

    private class FetchPlacesTask extends AsyncTask<Double, Void, Map<String, List<String>>> {
        @Override
        protected void onPreExecute() {
            super.onPreExecute();
            // ProgressBar is already shown in fetchLocation. Ensure layout is cleared.
            placeListLayout.removeAllViews();
            displayEmptyState("Fetching nearby places..."); // Temporary message
        }

        @Override
        protected Map<String, List<String>> doInBackground(Double... coords) {
            double lat = coords[0];
            double lon = coords[1];
            OkHttpClient client = new OkHttpClient();
            Map<String, List<String>> categorized = new HashMap<>();

            for (String type : TYPES) {
                HttpUrl url = new HttpUrl.Builder()
                        .scheme("https")
                        .host("maps.googleapis.com")
                        .addPathSegments("maps/api/place/nearbysearch/json")
                        .addQueryParameter("location", lat + "," + lon)
                        .addQueryParameter("radius", "2000") // 2 KM radius
                        .addQueryParameter("type", type)
                        .addQueryParameter("key", API_KEY)
                        .build();

                Request request = new Request.Builder().url(url).build();

                try (Response response = client.newCall(request).execute()) {
                    if (response.isSuccessful()) {
                        String json = response.body().string();
                        JSONObject root = new JSONObject(json);
                        String status = root.optString("status"); // Check status from Google Places API
                        if ("OK".equals(status) || "ZERO_RESULTS".equals(status)) {
                            JSONArray results = root.optJSONArray("results"); // Use optJSONArray for safety
                            if (results != null) {
                                List<String> places = new ArrayList<>();
                                for (int i = 0; i < results.length(); i++) {
                                    JSONObject place = results.getJSONObject(i);
                                    String name = place.optString("name");
                                    if (!name.isEmpty()) {
                                        places.add(name);
                                    }
                                }
                                if (!places.isEmpty()) {
                                    categorized.put(type, places);
                                }
                            }
                        } else {
                            Log.e(TAG, "Google Places API error for type " + type + ": " + status + " - " + root.optString("error_message"));
                        }
                    } else {
                        Log.e(TAG, "HTTP request failed for type " + type + ": " + response.code() + " " + response.message());
                    }
                } catch (IOException e) {
                    Log.e(TAG, "Network error fetching " + type + " places: " + e.getMessage(), e);
                    // Optionally, you could add an entry to categorized indicating a network error
                    // categorized.put(type, Arrays.asList("Network error for " + type));
                } catch (JSONException e) {
                    Log.e(TAG, "JSON parsing error for " + type + " places: " + e.getMessage(), e);
                    // categorized.put(type, Arrays.asList("Data format error for " + type));
                } catch (Exception e) { // Catch any other unexpected exceptions
                    Log.e(TAG, "Unexpected error fetching " + type + " places: " + e.getMessage(), e);
                }
            }
            return categorized;
        }

        @Override
        protected void onPostExecute(Map<String, List<String>> categorizedPlaces) {
            progressBar.setVisibility(View.GONE);
            placeListLayout.removeAllViews(); // Clear any "Fetching..." message

            if (categorizedPlaces.isEmpty()) {
                displayEmptyState("No nearby places found for the selected categories.");
                return;
            }

            for (Map.Entry<String, List<String>> entry : categorizedPlaces.entrySet()) {
                String label = getLabelForType(entry.getKey());

                TextView category = new TextView(MainActivity.this);
                category.setText(label);
                category.setTextSize(18f);
                category.setTypeface(null, Typeface.BOLD);
                category.setPadding(0, 12, 0, 4);
                placeListLayout.addView(category);

                for (String place : entry.getValue()) {
                    TextView item = new TextView(MainActivity.this);
                    item.setText("• " + place);
                    item.setPadding(8, 4, 0, 4);
                    placeListLayout.addView(item);
                }
            }
        }
    }

    private String getLabelForType(String type) {
        switch (type) {
            case "park": return "Parks 🏞️";
            case "shopping_mall": return "Shopping Malls 🛍️";
            case "church": return "Churches ⛪";
            case "tourist_attraction": return "Tourist Attractions 🗽";
            default: return type;
        }
    }

    @Override
    public void onRequestPermissionsResult(int requestCode,
                                           @NonNull String[] permissions,
                                           @NonNull int[] grantResults) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults);
        if (requestCode == 1001 &&
                grantResults.length > 0 &&
                grantResults[0] == PackageManager.PERMISSION_GRANTED) {
            fetchLocation();
        } else {
            Toast.makeText(this, "Location permission denied. Cannot fetch nearby places.", Toast.LENGTH_LONG).show();
            progressBar.setVisibility(View.GONE); // Hide progress if permission denied
            displayEmptyState("Location permission denied. Cannot find nearby places.");
        }
    }

    // Helper method to display an empty state message
    private void displayEmptyState(String message) {
        placeListLayout.removeAllViews();
        TextView emptyView = new TextView(MainActivity.this);
        emptyView.setText(message);
        emptyView.setPadding(0, 24, 0, 0); // Add some padding
        emptyView.setTextSize(16f);
        emptyView.setTextColor(getResources().getColor(android.R.color.darker_gray)); // A subtle color
        emptyView.setGravity(android.view.Gravity.CENTER_HORIZONTAL);
        placeListLayout.addView(emptyView);
    }
}