package com.desaysv.ivi.vdb.event.id.dsp.bean;

import android.os.Bundle;
import android.os.Parcel;
import android.os.Parcelable;

import com.desaysv.ivi.vdb.event.VDEvent;
import com.desaysv.ivi.vdb.event.base.VDKey;
import com.desaysv.ivi.vdb.event.base.VDValue;

/**
 * VDEventDsp.SOUND_FIELD yükü: Parcelable "data" içinde iki tam sayı (tür, değer). Sınıf, Desay vdbus.jar'daki aynı adlı sınıfın
 * (SVLauncher/SVVDSCarInfo dökümü, `VDDspSoundField`) alan düzenini ve sırasını birebir izler: tür ardından değer. Yazma ve okuma biçimi
 * DSP servisinde `VDDspBmt` / `VDDspChannel` ile aynı kalıptadır (tür = hangi parametre, değer = o parametrenin değeri).
 */
public class VDDspSoundField implements Parcelable {
    public static final Parcelable.Creator<VDDspSoundField> CREATOR = new Parcelable.Creator<VDDspSoundField>() {
        public VDDspSoundField createFromParcel(Parcel parcel) {
            return new VDDspSoundField(parcel);
        }

        public VDDspSoundField[] newArray(int i) {
            return new VDDspSoundField[i];
        }
    };

    private int mType = 0;
    private int mValue = VDValue.INVALID_INT;

    public VDDspSoundField() {
    }

    protected VDDspSoundField(Parcel parcel) {
        mType = parcel.readInt();
        mValue = parcel.readInt();
    }

    public static VDEvent createEvent(int id, VDDspSoundField field) {
        return new VDEvent(id, createPayload(field));
    }

    /** Okuma isteği: yalnızca tür dolu, değer geçersiz. */
    public static VDEvent createGetParam(int id, int type) {
        VDDspSoundField field = new VDDspSoundField();
        field.putType(type);
        return createEvent(id, field);
    }

    public static Bundle createPayload(VDDspSoundField field) {
        Bundle bundle = new Bundle();
        bundle.setClassLoader(VDDspSoundField.class.getClassLoader());
        bundle.putParcelable(VDKey.DATA, field);
        return bundle;
    }

    public static VDDspSoundField getValue(VDEvent event) {
        if (event == null || event.getPayload() == null) {
            return null;
        }
        event.getPayload().setClassLoader(VDDspSoundField.class.getClassLoader());
        Object data = event.getPayload().getParcelable(VDKey.DATA);
        return data instanceof VDDspSoundField ? (VDDspSoundField) data : null;
    }

    public int getType() {
        return mType;
    }

    public int getValue() {
        return mValue;
    }

    public void putType(int type) {
        mType = type;
    }

    public void putValue(int value) {
        mValue = value;
    }

    public int describeContents() {
        return 0;
    }

    public void writeToParcel(Parcel parcel, int flags) {
        parcel.writeInt(mType);
        parcel.writeInt(mValue);
    }
}
