var a = eval("true");

function box() {
    label: {
        try {
            if (a) throw 1;
        }
        catch (e) {
            break label;
        }
        throw 2;
    }

    return 'OK';
};

function box2() {
    label: {
        try {
            if (a) throw 1;
        }
        finally {
            break label;
        }
        throw 2;
    }

    return 'OK';
};

function box3() {
    label: {
        try {
            if (a) throw 1;
        }
        finally {
            break label;
        }
    }

    return 'OK';
};