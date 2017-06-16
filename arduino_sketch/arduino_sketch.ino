#include <Wire.h>

#define SLAVE_ADDRESS 0x09

void setup() {
  pinMode(13, OUTPUT);
  Serial.begin(9600); // start serial for output
  // initialize i2c as slave
  Wire.begin(SLAVE_ADDRESS);
  
  // define callbacks for i2c communication
  Wire.onReceive(receiveData);
  
  Serial.println("Ready!");
}

void loop() {
  delay(100);
}

// callback for received data
void receiveData(int byteCount){
  byte device=Wire.read();
  byte value=Wire.read();
  if(device==1 && value==0) Serial.println("Stopping!!");
  if(device==1 && value==-1) Serial.println("Moving BACKWARD!!");
  if(device==1 && value==1) Serial.println("Moving FORWARD!!");
  
}




