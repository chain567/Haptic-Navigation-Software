/*
  Bluetooth Vibrator Test Code (with Echo and PWM)

  --- MODIFIED to be "stateful" ---
  This code keeps the motor in its last state (OFF, LOW, or HIGH)
  until a new command is received.

  It also handles '3' as a one-shot "WARN" sequence with two HIGH pulses.
*/

// --- Pin Definition ---
const int motorPin = 10; // Pin connected to the vibration motor (must be a PWM '~' pin)

// --- Power Settings (0 = off, 255 = full power) ---
const int LOW_POWER = 120;  // PWM duty value: 120 out of 255.
const int HIGH_POWER = 255; // Maximum PWM duty value.

// --- State Variable ---
char currentState = '0'; // Default to '0' (OFF)

void setup() {
  // Initialize the motor pin
  pinMode(motorPin, OUTPUT);
  digitalWrite(motorPin, LOW); // Start with the motor off

  // Start Serial communication
  Serial.begin(9600);
  Serial.println("Bluetooth Vibrator Test Ready!");
  Serial.println("I will echo what you send.");
  Serial.println("Send '0' for OFF, '1' for LOW, '2' for HIGH, '3' for WARN.");
}

void loop() {

  // 1. Check if any *new* data has been sent
  if (Serial.available() > 0) {

    // 2. Read the incoming character
    char command = Serial.read();

    // 3. Echo the character back
    Serial.print("Received character: ");
    Serial.println(command);

    // 4. Update the state and print feedback
    switch (command) {

      case '1': // LOW Mode
        Serial.println("--> Setting state to LOW");
        currentState = '1'; // Update the remembered state
        break;

      case '2': // HIGH Mode
        Serial.println("--> Setting state to HIGH");
        currentState = '2'; // Update the remembered state
        break;

      case '0': // OFF Mode
        Serial.println("--> Setting state to OFF");
        currentState = '0'; // Update the remembered state
        break;

      // --- NEW ---
      // This is an "event", not a "state".
      // It plays a pulse and then sets the state to OFF.
      case '3': // WARN Mode (two brief HIGH pulses)
        Serial.println("--> Playing WARN pulse");

        analogWrite(motorPin, 0);
        delay(450); // Motor off for 450 ms
        analogWrite(motorPin, HIGH_POWER);
        delay(100); // High PWM for 100 ms
        // Continue the two-pulse sequence
        analogWrite(motorPin, 0);
        delay(300); // Motor off for 300 ms
        analogWrite(motorPin, HIGH_POWER);
        delay(100); // High PWM for 100 ms
        analogWrite(motorPin, 0);
        delay(450); // Motor off for 450 ms

        // After the pulse, go back to OFF state.
        // The app will send '2' soon to turn it on HIGH.
        analogWrite(motorPin, 0);
        currentState = '0';
        break;
      // --- END NEW ---

      default:
        // If we receive another character (like a newline),
        // just print a message and don't change the state.
        Serial.println("--> Unknown command, ignoring.");
        break;
    }
  }

  // 5. Apply the *remembered* state on EVERY loop
  // This block is now OUTSIDE the if(Serial.available())
  // This is what keeps the motor running.
  switch (currentState) {
    case '1':
      analogWrite(motorPin, LOW_POWER);
      break;
    case '2':
      analogWrite(motorPin, HIGH_POWER);
      break;
    case '0':
    default: // If state is '0' or somehow invalid, turn off.
      analogWrite(motorPin, 0);
      break;
  }

  // Keep the last state between commands. The warning sequence above uses blocking delays.
}
