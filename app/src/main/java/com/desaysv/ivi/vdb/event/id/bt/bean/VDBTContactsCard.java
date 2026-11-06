/*
 * OEM sınıfının birebir kopyası; Parcel alan sırası değiştirilmemeli (OEM servisi sınıf adıyla açar).
 * Tek fark: toString() gizlilik için isim/numara/adres/portre içermez.
 */
package com.desaysv.ivi.vdb.event.id.bt.bean;

import android.os.Parcel;
import android.os.Parcelable;

public class VDBTContactsCard implements Parcelable {
    public static final Parcelable.Creator<VDBTContactsCard> CREATOR = new Parcelable.Creator<VDBTContactsCard>() { // from class: com.desaysv.ivi.vdb.event.id.bt.bean.VDBTContactsCard.1
        /* JADX WARN: Can't rename method to resolve collision */
        @Override // android.os.Parcelable.Creator
        public VDBTContactsCard createFromParcel(Parcel parcel) {
            return new VDBTContactsCard(parcel);
        }

        /* JADX WARN: Can't rename method to resolve collision */
        @Override // android.os.Parcelable.Creator
        public VDBTContactsCard[] newArray(int r1) {
            return new VDBTContactsCard[r1];
        }
    };
    private int mNumberType = 1;
    private String mNumber = null;
    private String mId = null;

    @Override // android.os.Parcelable
    public int describeContents() {
        return 0;
    }

    public VDBTContactsCard() {
    }

    public int getNumberType() {
        return this.mNumberType;
    }

    public void putNumberType(int r1) {
        this.mNumberType = r1;
    }

    public String getNumber() {
        return this.mNumber;
    }

    public void putNumber(String str) {
        this.mNumber = str;
    }

    public String getId() {
        return this.mId;
    }

    public void putId(String str) {
        this.mId = str;
    }

    protected VDBTContactsCard(Parcel parcel) {
        readFromParcel(parcel);
    }

    @Override // android.os.Parcelable
    public void writeToParcel(Parcel parcel, int r2) {
        parcel.writeInt(this.mNumberType);
        parcel.writeString(this.mNumber);
        parcel.writeString(this.mId);
    }

    private void readFromParcel(Parcel parcel) {
        this.mNumberType = parcel.readInt();
        this.mNumber = parcel.readString();
        this.mId = parcel.readString();
    }

    @Override
    public String toString() {
        return "VDBTContactsCard{numberType=" + this.mNumberType + "}";
    }
}
